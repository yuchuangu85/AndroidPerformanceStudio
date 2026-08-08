# ADR-0005: Battery Profiler 产物采用兼容式演进

Battery Profiler 的 JSON 保持 `schemaVersion=1`，新增字段必须可选且有默认值；CSV 保持既有表头，Raw Bundle 保持既有路径与 manifest 字段，SQLite 仅做增量迁移，现有枚举值与操作入口不删除或改名。我们选择让新旧应用版本尽可能互读，而不是通过破坏性 schema 升级获得更自由的数据模型。
