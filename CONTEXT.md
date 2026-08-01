# Android Performance Studio

Android Performance Studio 将性能采集结果、分析结论与产生问题的 Android 源码关联起来，帮助用户从性能现象定位到可检查和修改的代码位置。

## Language

**源码工作区（Source Workspace）**:
一个只读注册的源码集合，可以来自本机 Android 工程目录、GitHub 仓库或 AOSP 在线源码；注册不意味着把整个源码复制到应用数据中或发送给 AI。
_Avoid_: 导入代码、上传工程、源码副本

**源码提供方（Source Provider）**:
提供源码内容和版本身份的来源，当前包括 Local、GitHub 和 AOSP。
_Avoid_: 导入类型、代码平台

**源码定位（Source Resolution）**:
将性能证据解析为源码工作区中的一个确定位置或一组有置信度排序的候选位置。
_Avoid_: AI 跳转、模糊搜索

**定位候选（Resolution Candidate）**:
由可验证的源码身份信息支持、可能对应某项性能证据的源码位置；AI 可以解释和排序候选，但不能凭空创建候选。
_Avoid_: AI 猜测、推荐文件

**源码位置（Source Location）**:
由源码工作区、不可变版本、相对路径以及可用时的行列范围共同标识的可导航位置。
_Avoid_: 文件路径、链接

**源码查看器（Source Viewer）**:
应用内统一呈现源码位置及其版本和匹配证据的只读界面，是源码定位的默认导航目标。
_Avoid_: 代码编辑器、AI 结果页

**源码快照（Source Snapshot）**:
源码工作区在某个确定版本上的身份，用于让分析结果和源码定位在源码更新后仍可复现。
_Avoid_: 最新代码、当前分支

**构建证据包（Build Evidence Bundle）**:
描述被分析应用或系统构建身份、符号和混淆映射的一组只读证据，用于把运行时名称和地址还原为源码身份。
_Avoid_: 构建产物、符号目录、APK 导入

**在线源码发现（Online Source Discovery）**:
在尚未缓存完整源码快照时，通过源码提供方搜索可能相关的源码；发现结果只有固定并校验到确定版本后才能成为定位候选。
_Avoid_: 在线源码定位、远程跳转

**虚拟源码工作区（Virtual Source Workspace）**:
逻辑上提供完整源码命名空间、但只按需获取和缓存相关项目或文件的源码工作区，主要用于大规模 AOSP 源码。
_Avoid_: AOSP 完整下载、在线文件列表

**源码索引（Source Index）**:
从确定源码快照中提取的文件、模块、资源和符号身份集合，为源码定位提供可验证候选。
_Avoid_: 向量库、AI 知识库、全文缓存

**分析会话（Analysis Session）**:
一次性能证据、AI 分析结果与一个或多个源码快照之间的稳定关联。
_Avoid_: AI 请求、聊天记录

**分析范围（Analysis Scope）**:
一次分析明确覆盖的当前选择或报告摘要，决定被收集的性能证据和允许使用的源码上下文。
_Avoid_: Prompt 长度、选中代码、上下文窗口

**性能证据（Performance Evidence）**:
由某个 Profiler 从采集数据中提取、可独立引用并复核的性能事实，例如布局节点、热点调用栈或采样区间。
_Avoid_: Prompt 数据、AI 上下文、性能结论

**分析结论（Analysis Finding）**:
基于一组性能证据形成的可操作问题说明，可以引用定位候选，但本身不拥有或生成源码位置。
_Avoid_: AI 回复、源码定位结果

**分析置信度（Analysis Confidence）**:
分析结论成立的可信程度，不用于判断源码跳转是否准确。
_Avoid_: AI 置信度、跳转置信度

**定位置信度（Resolution Confidence）**:
定位候选与性能证据匹配程度的确定性等级，由源码定位过程产生，不受分析置信度影响。
_Avoid_: AI 评分、相似度
