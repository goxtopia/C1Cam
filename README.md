# C1Cam

[中文](#介绍) | [English](#english)

## 介绍

C1Cam 是一款独立的开源 Android 相机应用，围绕手动拍摄、画幅构图和色彩处理设计。可以用它日常拍摄、体验不同焦段与宽画幅，也可以搭配沧野 C1（Cangye C1）等外接光学设备使用。

### 拍摄与构图

- **手动与自动对焦**：支持手动对焦距离控制、自动对焦、点击对焦及 AF 锁定。
- **曝光控制**：提供 EV 调节、AE 锁定和多种测光方式，XPAN 模式带直方图与曝光信息。
- **画幅与焦段**：支持经典焦段数码裁切、自定义宽高比、画幅参考线，以及多种 XPAN 宽画幅取景布局。
- **四点定位与透视复原**：在相机预览中进入编辑模式，拖动四个角点设置区域；按指定比例复原照片，也适用于沧野 C1 等设备。
- **方向控制**：拍摄页与设置页固定竖屏，不跟随系统自动旋转；保留应用内的重力感应控件与成片方向处理。

### 色彩与画质

- 内置多款 LUT，支持导入 `.cube` 文件，配合色调映射调整成片风格。
- 提供彩色噪点抑制、按 ISO 自动调整的去噪选项，以及高 ISO 像素合并。
- 支持关闭设备内置降噪与锐化（具体效果取决于相机硬件）。
- 静态照片使用 OpenGL 处理裁切、透视、旋转和 LUT；GPU 不可用时回退到 CPU。

### 照片保存

在设置中选择 **JPG、PNG 或 JPG + DNG**，JPG 压缩质量可调。

**JPG + DNG** 从同一次曝光获取两个文件：JPG 按当前裁切、LUT 和画质设置处理；DNG 保留传感器原始数据及拍摄元数据，不应用这些成片处理。两个文件使用相同时间戳命名，保存至 `Pictures/C1Cam`。

DNG 使用独立后台线程写入，与 JPG 的 GPU 处理和编码保存并行；拍摄采用有界队列，避免连续拍摄堆积过多内存。实际速度取决于 RAW 尺寸、设备存储和处理设置。

DNG 需要相机硬件支持 RAW 与 JPG 同时输出。设备不支持 RAW，或当前预览组合无法使用 RAW 时，会提示并保存 JPG。

### 其他功能与已知限制

- 支持预览与复原画面切换、闲置超时设置、锁屏相机入口。
- Sport（运动）模式和手动对焦在部分设备上可能冲突，导致预览黑屏；遇到此情况请关闭其中一项。
- WDR 为实验功能，效果与兼容性仍需按设备验证。

## 构建与签名

需要 JDK 17、Android SDK 36 和 Android Build Tools 36.0.0。Gradle Wrapper 会下载项目使用的 Gradle 版本。

```bash
./gradlew testDebugUnitTest lintRelease assembleRelease
```

签名后的 APK 输出到：

```text
app/build/outputs/apk/release/app-release.apk
```

固定 release 签名已配置到 Gradle，以后的 release 构建会继续使用同一个 key：

- Key：[signing/c1cam-release.jks](signing/c1cam-release.jks)
- 密码、别名与路径：[signing/release.properties](signing/release.properties)
- 签名说明：[signing/README.md](signing/README.md)

按项目约定，key 与密码文件均公开保留在仓库中。请保留该签名身份，以便后续安装包能够覆盖更新。

## English

C1Cam is an independent, open-source Android camera focused on manual shooting, framing and color processing. Use it as an everyday camera, explore digital focal-length crops and panoramic formats, or pair it with optical accessories such as the Cangye C1.

### Features

- Manual and automatic focus, tap to focus, AF/AE locks and exposure compensation.
- Digital focal-length crops, custom aspect ratios, framing guides and XPAN panoramic layouts with metering and exposure information.
- Editable four-corner framing with perspective correction, including support for Cangye C1 setups.
- Built-in and importable `.cube` LUTs, tone mapping, chroma denoise and high-ISO pixel binning.
- OpenGL still-image processing with a CPU fallback.
- A fixed portrait interface with the existing gravity-based control and photo orientation behavior.
- JPG, PNG and **JPG + DNG** output, with adjustable JPG quality.

JPG + DNG captures both outputs from the same exposure. The JPG receives the selected crop, color and image processing; the DNG preserves the original sensor data. DNG writes run on a separate background worker alongside JPG processing and saving. Both files share a timestamp-based name in `Pictures/C1Cam`. RAW capture requires compatible camera hardware and stream configuration; otherwise the app reports the limitation and saves JPG only.

Sport mode may conflict with manual focus on some devices. WDR remains experimental.

Build with JDK 17 and Android SDK 36 using `./gradlew assembleRelease`. The signed APK is written to `app/build/outputs/apk/release/app-release.apk`. The permanent release key and its credentials are intentionally included in [signing/](signing/) and used automatically by Gradle.
