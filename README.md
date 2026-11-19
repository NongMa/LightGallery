# 清影相册 · LightGallery

一个**纯本地、不联网**的 Android 相册，界面设计语言对齐系统相册（MIUI 相册式）与 iOS 相册。

## 特性

- **时光**：按拍摄日期聚合成日 / 月 / 年三级视图，捏合手势切换档位，支持从月、年逐级钻取
- **相册**：按文件夹自动归组，一眼看清每个相册内容
- **视频**：单独的视频流，直接播放
- **实况照片**：自动识别本机实况照片（MotionPhoto / MicroVideo），缩略图带角标，网格中可播放
- **快速就位条**：右边缘年/月/年粒度刻度 + 拖动气泡，支持慢速与快速 fling
- **顶部单条件筛选**：图片 / 视频 / 实况 / 收藏，即时生效
- **液态玻璃标签栏**：底部悬浮玻璃效果，末行可滚动到其下方
- **多选、收藏、删除**：与系统相册一致的操作流
- **捏合缩放**：向内合拢 = 放大单元格，向外张开 = 缩小，档位与日/月/年联动

全部数据来自本机 MediaStore，**没有任何网络权限**。

## 构建

```bash
./gradlew :app:assembleRelease
```

产物位于 `app/build/outputs/apk/release/app-release.apk`（使用 debug 签名便于覆盖安装体验）。

要求：JDK 17+，Android SDK（compileSdk 36）。

## 许可与致谢

- [LazyColumnScrollbar](https://github.com/nanihadesuka/LazyColumnScrollbar)（MIT）— 右边缘就位条
- [Backdrop](https://github.com/kyant0/Backdrop) — 液态玻璃渲染
- [Coil 3](https://coil-kt.github.io/coil/)（Apache-2.0）— 图片加载
- Compose / Material3（Apache-2.0）

应用图标为自绘矢量（八瓣花），非任何官方素材。
