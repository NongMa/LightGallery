package com.liu.gallery.vm

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liu.gallery.data.Album
import com.liu.gallery.data.MediaFilter
import com.liu.gallery.data.MediaType
import com.liu.gallery.data.GroupMode
import com.liu.gallery.data.MediaGroup
import com.liu.gallery.data.MediaItem
import com.liu.gallery.data.MediaRepository
import com.liu.gallery.data.SortOrder
import com.liu.gallery.data.groupByDate
import com.liu.gallery.data.LivePhotoLoader
import com.liu.gallery.data.VideoThumbLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 全屏查看器状态：当前列表与页码 */
data class ViewerState(val items: List<MediaItem>, val index: Int)

/** 需要系统授权确认的操作类型 */
enum class ConsentKind { DELETE, FAVORITE, RENAME }

/** 等待用户确认的系统请求 */
data class PendingConsent(
    val kind: ConsentKind,
    val uris: List<Uri>,
    val favoriteValue: Boolean = false,
    val newName: String? = null,
    val pendingIntent: PendingIntent,
)

/**
 * 应用主视图模型：媒体列表、分组/过滤/排序、多选、查看器、系统授权流。
 * 所有耗时操作都在 IO 协程中完成，UI 只收集状态。
 */
class AlbumViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MediaRepository(app)

    // 设置页持久化的默认时间线视图（仅在冷启动/切换时读取一次）
    private val prefs = app.getSharedPreferences("qingying_settings", android.content.Context.MODE_PRIVATE)

    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _groupMode = MutableStateFlow(
        runCatching { GroupMode.valueOf(prefs.getString(KEY_DEFAULT_GROUP_MODE, GroupMode.DAY.name)!!) }
            .getOrDefault(GroupMode.DAY)
    )
    val groupMode: StateFlow<GroupMode> = _groupMode.asStateFlow()

    private val _filter = MutableStateFlow(MediaFilter())
    val filter: StateFlow<MediaFilter> = _filter.asStateFlow()

    // 全量实况识别结果：null = 尚未扫描；只在启用"仅实况"时按需建立
    private val _liveIds = MutableStateFlow<Set<Long>?>(null)
    val liveIds: StateFlow<Set<Long>?> = _liveIds.asStateFlow()
    private val _liveScanning = MutableStateFlow(false)
    val liveScanning: StateFlow<Boolean> = _liveScanning.asStateFlow()
    private var liveScanJob: Job? = null

    private val _sort = MutableStateFlow(SortOrder.NEWEST)
    val sort: StateFlow<SortOrder> = _sort.asStateFlow()

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    private val _viewer = MutableStateFlow<ViewerState?>(null)
    val viewer: StateFlow<ViewerState?> = _viewer.asStateFlow()

    private val _consent = MutableStateFlow<PendingConsent?>(null)
    val consent: StateFlow<PendingConsent?> = _consent.asStateFlow()

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    private var refreshJob: Job? = null

    // ------------------------------------------------------------- 派生数据

    /** 过滤 + 排序后的主列表（重活在 Default 线程，避免占用主线程） */
    val filtered: StateFlow<List<MediaItem>> =
        combine(_items, _filter, _sort, _liveIds) { list, filter, sort, liveIds ->
            val filtered = if (!filter.isActive) {
                list
            } else {
                // matches 内部读 item.localDate 缓存，不再全表重复解析日期
                list.filter { item -> filter.matches(item, liveIds) }
            }
            if (sort == SortOrder.OLDEST) filtered.asReversed() else filtered
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 本机内容涉及的年份，倒序（供筛选面板选择） */
    val availableYears: StateFlow<List<Int>> =
        _items.map { list -> list.map { it.year }.distinct().sortedDescending() }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 时间线分组（日 / 月 / 年） */
    val groups: StateFlow<List<MediaGroup>> =
        combine(filtered, _groupMode) { list, mode -> groupByDate(list, mode) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 相册（文件夹）列表，按最新内容排序（与排序切换无关，不再订阅 _sort） */
    val albums: StateFlow<List<Album>> =
        _items.map { list -> buildAlbums(list) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 视频页内容 */
    val videos: StateFlow<List<MediaItem>> =
        combine(_items, _sort) { list, sort ->
            val only = list.filter { it.isVideo }
            if (sort == SortOrder.OLDEST) only.asReversed() else only
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun buildAlbums(items: List<MediaItem>): List<Album> {
        if (items.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<MediaItem>>(32)
        for (item in items) grouped.getOrPut(item.albumId) { ArrayList() }.add(item)
        return grouped.mapNotNull { (id, list) ->
            if (list.isEmpty()) null
            else {
                var imageCount = 0
                var videoCount = 0
                for (item in list) {
                    if (item.isVideo) videoCount++ else imageCount++
                }
                Album(
                    id = id,
                    name = list.first().albumName,
                    imageCount = imageCount,
                    videoCount = videoCount,
                    cover = list.first(),
                    items = list,
                )
            }
        }.sortedByDescending { it.cover.dateTakenMillis }
    }

    // ------------------------------------------------------------- 数据加载

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _loading.value = true
            val old = _items.value
            val list = repo.loadMedia()
            // 差集：被删除/移出媒体库的条目，清掉视频帧与实况照片缓存，
            // 避免 id 复用后展示脏缓存；同时删除磁盘上残留的 live_*.mp4
            if (old.isNotEmpty()) {
                val newIds = list.mapTo(HashSet(list.size)) { it.id }
                val removed = old.filter { it.id !in newIds }
                if (removed.isNotEmpty()) {
                    removed.forEach {
                        VideoThumbLoader.evict(it)
                        LivePhotoLoader.evict(it)
                    }
                    LivePhotoLoader.clearStale(getApplication(), newIds)
                }
            }
            _items.value = list
            // 全量实况集合随列表失效：启用中则立即按新列表重扫，否则等下次启用时再扫
            if (_liveIds.value != null || _filter.value.liveOnly) {
                _liveIds.value = null
                if (_filter.value.liveOnly) startLiveScan()
            }
            syncAfterReload(list)
            _loading.value = false
        }
    }

    /** 重载后修正查看器与选中集（可能有条目已删除） */
    private fun syncAfterReload(list: List<MediaItem>) {
        val validIds = list.mapTo(HashSet(list.size)) { it.id }
        _selection.update { old ->
            if (old.isEmpty()) old else old.filter { it in validIds }.toSet()
        }
        val v = _viewer.value ?: return
        val remain = v.items.filter { it.id in validIds }
        if (remain.isEmpty()) {
            _viewer.value = null
            return
        }
        val currentId = v.items.getOrNull(v.index)?.id
        val newIndex = remain.indexOfFirst { it.id == currentId }
            .takeIf { it >= 0 }
            ?: v.index.coerceIn(0, remain.lastIndex)
        _viewer.value = v.copy(items = remain, index = newIndex)
    }

    // ------------------------------------------------------------- 过滤/分组

    fun setGroupMode(mode: GroupMode) {
        _groupMode.value = mode
    }

    /** 设置页：记录默认时间线视图并立即生效 */
    fun setDefaultGroupMode(mode: GroupMode) {
        prefs.edit().putString(KEY_DEFAULT_GROUP_MODE, mode.name).apply()
        _groupMode.value = mode
    }

    fun toggleTypeFilter(type: MediaType) {
        _filter.update { it.selectType(type) }
        _selection.value = emptySet()
    }

    fun toggleFavoriteFilter() {
        _filter.update { it.selectFavorite() }
        _selection.value = emptySet()
    }

    /** 切换"仅实况"：首次启用时在后台全量识别（几千张 JPEG 只读文件头，通常几秒完成） */
    fun toggleLiveOnly() {
        val updated = _filter.value.selectLive()
        _filter.value = updated
        _selection.value = emptySet()
        if (updated.liveOnly && _liveIds.value == null) {
            // 同步置位，避免 UI 先闪一帧未过滤的空列表
            _liveScanning.value = true
            startLiveScan()
        }
    }

    private fun startLiveScan() {
        if (liveScanJob?.isActive == true) {
            _liveScanning.value = true
            return
        }
        _liveScanning.value = true
        liveScanJob = viewModelScope.launch {
            val set = withContext(Dispatchers.IO) {
                LivePhotoLoader.scanLiveIds(getApplication(), _items.value)
            }
            _liveIds.value = set
            _liveScanning.value = false
        }
    }

    fun toggleYearFilter(year: Int) {
        _filter.update { it.selectYear(year) }
        _selection.value = emptySet()
    }

    fun clearFilter() {
        _filter.value = MediaFilter()
        _selection.value = emptySet()
    }

    fun toggleSort() {
        _sort.update { if (it == SortOrder.NEWEST) SortOrder.OLDEST else SortOrder.NEWEST }
    }

    // --------------------------------------------------------------- 多选

    fun toggleSelect(id: Long) {
        _selection.update { if (id in it) it - id else it + id }
    }

    /** 全选 / 取消全选：已全部选中时再点则清空 */
    fun toggleSelectAll(ids: Collection<Long>) {
        val target = ids.toSet()
        _selection.update { if (it.size == target.size && it.containsAll(target)) emptySet() else target }
    }

    fun clearSelection() {
        if (_selection.value.isNotEmpty()) _selection.value = emptySet()
    }

    fun isSelected(id: Long): Boolean = id in _selection.value

    // ------------------------------------------------------------- 查看器

    fun openViewer(list: List<MediaItem>, index: Int) {
        if (list.isEmpty()) return
        _viewer.value = ViewerState(list, index.coerceIn(0, list.lastIndex))
    }

    fun closeViewer() {
        _viewer.value = null
    }

    fun viewerPage(page: Int) {
        _viewer.update { v -> if (v == null) v else v.copy(index = page) }
    }

    // ------------------------------------------------------------ 删除/收藏

    /** 删除（Android 11+ 走系统确认弹窗，旧版本直接删） */
    fun requestDelete(targets: List<MediaItem>) {
        if (targets.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            val pi = repo.createTrashIntent(targets.map { it.uri })
            if (pi != null) {
                _consent.value = PendingConsent(
                    kind = ConsentKind.DELETE,
                    uris = targets.map { it.uri },
                    pendingIntent = pi,
                )
                return
            }
        }
        viewModelScope.launch {
            val ok = repo.deleteDirect(targets)
            toast(if (ok) "已删除 ${targets.size} 项" else "删除失败，请检查存储权限")
            if (ok) {
                _selection.update { old -> old.filterNot { id -> targets.any { it.id == id } }.toSet() }
            }
            refresh()
        }
    }

    /** 收藏 / 取消收藏 */
    fun setFavorite(targets: List<MediaItem>, favorite: Boolean) {
        if (targets.isEmpty()) return
        if (Build.VERSION.SDK_INT < 31) {
            toast("需要 Android 12 及以上版本")
            return
        }
        val uris = targets.map { it.uri }
        viewModelScope.launch {
            if (repo.setFavoriteDirect(uris, favorite)) {
                toast(if (favorite) "已收藏 ${targets.size} 项" else "已取消收藏")
                refresh()
                return@launch
            }
            val pi = repo.createFavoriteIntent(uris, favorite)
            if (pi == null) {
                toast("无法修改收藏状态")
            } else {
                _consent.value = PendingConsent(
                    kind = ConsentKind.FAVORITE,
                    uris = uris,
                    favoriteValue = favorite,
                    pendingIntent = pi,
                )
            }
        }
    }

    /** 重命名 */
    fun rename(item: MediaItem, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == item.name) return
        viewModelScope.launch {
            if (Build.VERSION.SDK_INT >= 30) {
                val pi = repo.createWriteIntent(listOf(item.uri))
                if (pi == null) {
                    toast("无法重命名")
                } else {
                    _consent.value = PendingConsent(
                        kind = ConsentKind.RENAME,
                        uris = listOf(item.uri),
                        newName = trimmed,
                        pendingIntent = pi,
                    )
                }
            } else {
                val ok = repo.renameDirect(item, trimmed)
                toast(if (ok) "已重命名" else "重命名失败")
                if (ok) refresh()
            }
        }
    }

    /** 系统授权弹窗回调 */
    fun onConsentResult(resultCode: Int) {
        val pending = _consent.value ?: return
        _consent.value = null
        if (resultCode != Activity.RESULT_OK) {
            toast("已取消")
            return
        }
        viewModelScope.launch {
            when (pending.kind) {
                ConsentKind.DELETE -> {
                    toast("已将 ${pending.uris.size} 项移入回收站")
                    refresh()
                }

                ConsentKind.FAVORITE -> {
                    val ok = repo.applyFavorite(pending.uris, pending.favoriteValue)
                    toast(
                        when {
                            !ok -> "操作失败"
                            pending.favoriteValue -> "已收藏 ${pending.uris.size} 项"
                            else -> "已取消收藏"
                        }
                    )
                    refresh()
                }

                ConsentKind.RENAME -> {
                    val uri = pending.uris.firstOrNull()
                    val name = pending.newName
                    val ok = uri != null && name != null && repo.applyRename(uri, name)
                    toast(if (ok) "已重命名" else "重命名失败")
                    refresh()
                }
            }
        }
    }

    private fun toast(message: String) {
        _toast.tryEmit(message)
    }

    private companion object {
        const val KEY_DEFAULT_GROUP_MODE = "default_group_mode"
    }
}

/** 供 UI 展示“共 N 项”的去抖标记（预留扩展） */
@OptIn(FlowPreview::class)
internal fun unusedFlowMarker() = Unit
