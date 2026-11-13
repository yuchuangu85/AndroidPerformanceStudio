# Compose 采样基准

`compose-sampling.tsv` 是通过执行实际的 Compose
`SamplingTemplate` 和 `SimpleperfRecordCommand` 生成的，不是由第二套 Java
实现生成的。`SamplingOracle.java` 仅作为驱动程序，将真实命令筛选为
事件/速率/调用图参数。此 fixture 有意不声称 target、transport、duration
limits 和 permissions 等价。

46 个用例覆盖 app/non-app 范围下的五个模板，以及两个 events、三个 scope、
frequency/period 和三种 callgraph 模式。其中包含模板 duration。
`provenance.json` 记录当前基准 Kotlin 源文件和驱动程序的哈希值。

编译 Compose capture 模块后重新生成：

```sh
cd desktop-viewer
./gradlew -p simpleperf-viewer :capture-simpleperf:classes --offline
cd ..
# CLASSES is capture-simpleperf/build/classes/kotlin/main; STDLIB is the
# kotlin-stdlib JAR for the version pinned in desktop-viewer/gradle/libs.versions.toml.
javac -cp "$CLASSES:$STDLIB" -d "$OUT" rust-desktop/crates/aps-capture/tests/fixtures/SamplingOracle.java
java -cp "$OUT:$CLASSES:$STDLIB" SamplingOracle > rust-desktop/crates/aps-capture/tests/fixtures/compose-sampling.tsv
```

Rust 回归测试在测试时直接使用已提交的 fixture，不依赖 Java/Gradle。更新 fixture
需要新的 Compose 执行证据和源文件哈希值。

`DurationOracle.java` 同样生成 `compose-duration.tsv`：七个用例覆盖省略手动
duration、整数/小数秒以及纳秒表示法。驱动程序调用真实的 Kotlin 命令构建器，
并使用 BigDecimal 规范化科学计数法和十进制形式；它不会在 Java 中复刻该构建器。
`duration-provenance.json` 记录其源文件/驱动程序哈希值。Rust 保留有限的、纳秒
精度的 300 秒 timed limit；对于任意上限以及无效/无限输入，不声称与 Compose 等价。
