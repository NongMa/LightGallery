package com.liu.gallery

import android.graphics.PointF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.liu.gallery.data.GroupMode
import com.liu.gallery.vm.AlbumViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 捏合换档链真机测试（MIUI 同规则）：
 *
 * 档位链 DAY(4) -> DAY(5) -> MONTH(8) -> YEAR(16)，一次捏合走一档：
 * - 合拢（手指靠近）= 向密集档：DAY->DAY->MONTH->YEAR，到 YEAR 后再合拢不生效
 * - 张开（手指分开）= 向大图档：反向走，到 DAY(4) 后再张开不生效
 *
 * UiAutomation 注入合成双指事件（设备无 root，sendevent 被 SELinux 拦），
 * 每步截图存到应用外部目录，供人工核对列数变化。
 */
@RunWith(AndroidJUnit4::class)
class PinchStepTest {

    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ui get() = inst.uiAutomation

    private fun now() = SystemClock.uptimeMillis()
    private fun sleep(ms: Long) = Thread.sleep(ms)

    private fun obtain(downTime: Long, action: Int, a: PointF, b: PointF?): MotionEvent {
        val count = if (b != null) 2 else 1
        val props = Array(count) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { i ->
            val p = if (i == 0) a else b!!
            MotionEvent.PointerCoords().apply {
                x = p.x
                y = p.y
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            downTime, now(), action, count, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
    }

    private fun inject(action: Int, a: PointF, b: PointF?, downTime: Long) {
        val ev = obtain(downTime, action, a, b)
        ui.injectInputEvent(ev, true)
        ev.recycle()
    }

    /**
     * 一次完整捏合。前两步各 10px/finger：先越过 1.2×touchSlop 门槛
     * （进入时偏离恰为 10%，不触发快速满进度），之后走渐进对数进度。
     * @param together true=合拢（换更密集档），false=张开（换更大图档）
     */
    private fun pinch(together: Boolean) {
        val downTime = now()
        val y = 1900f
        var lx = 520f
        var rx = 920f
        val ptrDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val ptrUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

        inject(MotionEvent.ACTION_DOWN, PointF(lx, y), null, downTime)
        sleep(50)
        inject(ptrDown, PointF(lx, y), PointF(rx, y), downTime)
        sleep(80)

        val steps =
            if (together) intArrayOf(10, 10, 25, 25, 25, 25, 25, 25)
            else intArrayOf(10, 10, 30, 30, 30, 30, 30, 30, 30, 30)
        for (s in steps) {
            if (together) { lx += s; rx -= s } else { lx -= s; rx += s }
            inject(MotionEvent.ACTION_MOVE, PointF(lx, y), PointF(rx, y), downTime)
            sleep(35)
        }

        inject(ptrUp, PointF(lx, y), PointF(rx, y), downTime)
        sleep(40)
        inject(MotionEvent.ACTION_UP, PointF(lx, y), null, downTime)
        sleep(600) // 等换档落定
    }

    private fun vmOf(scenario: ActivityScenario<MainActivity>): AlbumViewModel {
        var vm: AlbumViewModel? = null
        scenario.onActivity { a -> vm = ViewModelProvider(a).get(AlbumViewModel::class.java) }
        return vm!!
    }

    private fun mode(scenario: ActivityScenario<MainActivity>): GroupMode =
        vmOf(scenario).groupMode.value

    private fun shot(name: String) {
        val bmp = ui.takeScreenshot() ?: return
        val dir = inst.targetContext.getExternalFilesDir("shots") ?: return
        File(dir, "$name.png").outputStream().use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bmp.recycle()
    }

    @Test
    fun pinchWalksDayMonthYearChain() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // 等媒体加载完成
            var loaded = false
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && !loaded) {
                scenario.onActivity { a ->
                    val vm = ViewModelProvider(a).get(AlbumViewModel::class.java)
                    loaded = !vm.loading.value && vm.filtered.value.isNotEmpty()
                }
                if (!loaded) sleep(300)
            }
            assertTrue("媒体应加载完成", loaded)
            shot("01_day_4col")

            // 1) 合拢 -> DAY(5)：分组仍是日
            pinch(together = true)
            assertEquals("第1次合拢：仍在日档", GroupMode.DAY, mode(scenario))
            shot("02_day_5col")

            // 2) 合拢 -> MONTH
            pinch(together = true)
            assertEquals("第2次合拢：月档", GroupMode.MONTH, mode(scenario))
            shot("03_month_8col")

            // 3) 合拢 -> YEAR
            pinch(together = true)
            assertEquals("第3次合拢：年档", GroupMode.YEAR, mode(scenario))
            shot("04_year_16col")

            // 4) 年档边界：再合拢不生效
            pinch(together = true)
            assertEquals("年档边界：合拢不换档", GroupMode.YEAR, mode(scenario))
            shot("05_year_boundary")

            // 5) 张开 -> MONTH
            pinch(together = false)
            assertEquals("第1次张开：回月档", GroupMode.MONTH, mode(scenario))
            shot("06_month_back")

            // 6) 张开 -> DAY
            pinch(together = false)
            assertEquals("第2次张开：回日档", GroupMode.DAY, mode(scenario))

            // 7) 张开 -> DAY(4)（分组不变，截图核对列数）
            pinch(together = false)
            assertEquals("第3次张开：仍在日档", GroupMode.DAY, mode(scenario))
            shot("07_day_4col_back")

            // 8) 日档边界：再张开不生效；随后两次合拢应按
            //    DAY(4)->DAY(5)->MONTH 走完准时到达月档（若边界越界会错位）
            pinch(together = false)
            assertEquals("日档边界：张开不换档", GroupMode.DAY, mode(scenario))
            pinch(together = true)
            pinch(together = true)
            assertEquals("边界后重走：准时到月档", GroupMode.MONTH, mode(scenario))
        }
    }
}
