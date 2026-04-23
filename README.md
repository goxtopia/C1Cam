---

# 沧野 C1 专属相机 (Cangye C1 Camera)

🇨🇳 [中文版](#介绍) | 🇬🇧 [English](#desc)

---

## 介绍

这是一个**“几乎”**专门为**沧野C1（Cangye C1）大画幅相机**深度定制的 Android 拍摄 APP。针对沧野C1用起来麻爪的地方做了针对优化，还保留了作为独立摄影 APP 把玩的乐趣。

### ✨ 核心特性

*   🖼️ **自定义画幅与自动复原**：支持在预览画面中设定一个四点定位取景框，并设定矩形缩放后的目标比例。点击拍摄后，APP 将自动根据设定的参数生成巨型复原后的照片。
*   🎛️ **核心手动控制**：支持**手动对焦 (MF)**，解决相机接到C1之后拉风箱的问题，以及提供两档 **曝光补偿 (EV)** 调节，满足复杂光线下的拍摄需求。
*   🎨 **LUT 色彩滤镜**：内置多款 LUT（色彩查找表）文件，拍完直接输出风格化的相片。
*   ⚡ **OpenGL 硬件加速**：底层采用 OpenGL 进行图像渲染与处理，提升照片生成与算法处理速度。
*   📷 **追求极致纯净画质**：支持强制**关闭 Android Camera 自带的降噪算法**。摒弃数码涂抹感，为你带来更加纯粹、细节更丰富的大画幅摄影体验。
*   🌈 **去彩噪算法**：为了配合纯净画质模式，APP 内置了降低彩色噪点（Chroma Noise）的功能。**强烈推荐与“关闭自带降噪”功能搭配使用**。
*   🌅 **WDR（宽动态范围）**：支持 WDR 拍摄模式，保留更多高光与阴影细节（*注：体验尚不完善*）。
*   ✂️ **退坑也能玩（数码裁切）**：如果你不再使用沧野C1相机，APP 内置了几个常见的经典焦段预设，你可以将它当作一个普通相机，体验纯粹的“数码裁切”摄影乐趣。

### 🐛 已知问题 (Known Issues)

*   **⚠️ Sport（运动）模式冲突**：Sport 模式的设计逻辑类似于“快门优先”，旨在尽可能保证高快门速度。但经实测，在某些特定情况下，该模式与“手动对焦”功能存在冲突，可能会导致预览界面黑屏且无法使用。请在当前版本中尽量避免同时使用这两个功能。

---

## desc

An Android camera application **"almost"** exclusively tailored for the **Cangye C1 Large Format Camera**. It is designed to be used as a partner of the Cangye C1 camera while keeping it fun enough to be used as a standalone camera app.

### ✨ Features

*   🖼️ **Custom Framing & Auto-Restoration**: Allows you to define a four-point posting viewfinder frame on the screen and set a target scaling ratio. Once you press the shutter, the app automatically generates the restored photo based on your parameters. What you see is what you get.
*   🎛️ **Manual Controls**: Supports **Manual Focus (MF)** and provides a two-step **Exposure Value (EV)** adjustment to handle complex lighting conditions.
*   🎨 **LUT Support**: Comes with several built-in LUT (Look-Up Table) files, allowing you to output stylized, cinematic, or film-like photos instantly.
*   ⚡ **OpenGL Acceleration**: Utilizes OpenGL for underlying image rendering and processing, significantly improving the speed of photo generation and algorithm execution.
*   📷 **Pure Image Quality**: Supports forcibly **disabling the Android Camera's built-in noise reduction algorithms**. Say goodbye to digital smoothing and capture purer photos with rich, raw details.
*   🌈 **Chroma Noise Reduction**: Features a dedicated tool to reduce color noise. **Highly recommended to be used in conjunction with the "Disable Built-in Noise Reduction" feature** for the best texture and image quality.
*   🌅 **WDR (Wide Dynamic Range)**: Includes an experimental WDR shooting mode to preserve more highlight and shadow details (*Note: This feature is still a work-in-progress and not yet perfect*).
*   ✂️ **Standalone Digital Cropping**: Even if you stop using the Cangye C1, the app includes several built-in classic focal length presets. You can continue to use it as a standard camera to enjoy the fun of digital cropping photography.

### 🐛 Known Issues

*   **⚠️ Sport Mode Conflict**: The "Sport Mode" acts similarly to Shutter Priority, aiming to maintain a high shutter speed. However, tests have shown that under certain circumstances, this mode conflicts with the "Manual Focus" feature. This conflict may result in a black preview screen, rendering the app unusable. Please avoid combining these two settings in the current version.
