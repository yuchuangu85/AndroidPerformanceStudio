# ADR-0002: Java/Kotlin 分配跟踪先做导入分析，ART 实时录制为后期增强

Track Memory Consumption — Java/Kotlin Allocations 的首发形态是**导入型**：复用 Perfetto 底座（heap graph / ART 分配事件）导入后桌面端分析；Android Studio 式的设备端 ART 实时录制（"Record"）不作为首发范围。

**Why**：导入型复用现有 perfetto-viewer、实现快、可独立交付；ART 实时录制是 8 项里设备耦合最深、工作量最大的，放后期增强。这也意味着首发形态不是 Studio 的实时录制体验——这是有意的取舍，后续补充不推翻导入能力。

**Considered Options**：
- 导入型（采用）——快、复用底座、可交付。
- ART 设备端实时录制——最接近 Studio 原版，但强绑定设备端且工作量大。
