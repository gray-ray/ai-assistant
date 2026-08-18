# DocumentUploadServiceImpl 上传业务缺陷修复计划

## 1. 背景

当前 `DocumentUploadServiceImpl` 已支持基础上传能力：校验空文件、创建本地上传目录、保存文件、写入 `document_info`、触发异步文档处理。但该实现仍存在文件安全、并发覆盖、数据一致性、状态语义和存储可扩展性方面的缺陷。

本计划针对以下文件展开修复：

- `ai-assistant-document/src/main/java/org/grayray/aiassistant/document/service/impl/DocumentUploadServiceImpl.java`
- `ai-assistant-document/src/main/java/org/grayray/aiassistant/document/service/impl/DocumentProcessServiceImpl.java`
- `ai-assistant-document/src/main/java/org/grayray/aiassistant/document/controller/DocumentController.java`
- `ai-assistant-common/src/main/java/org/grayray/aiassistant/common/config/WebMvcConfig.java`
- `ai-assistant-server/src/main/resources/application.yaml`

## 2. 当前缺陷

### 2.1 文件名可能冲突

当前使用 `System.currentTimeMillis() + extension` 生成存储文件名。同一毫秒内并发上传同扩展名文件时，可能产生相同文件名并覆盖已有文件。

影响：

- 文件内容被覆盖。
- 数据库记录与磁盘文件不再一一对应。
- 异步解析可能解析到错误文件。

### 2.2 文件类型校验不足

当前只根据原始文件名截取扩展名，没有校验扩展名白名单、`Content-Type` 和文件头。任意文件都可以落盘。

同时，`DocumentProcessServiceImpl` 对非 PDF 文件直接标记为 `completed`，这会让用户误以为文档已成功解析并可检索。

影响：

- 上传目录可能被写入非法文件。
- 状态展示误导用户。
- 当前 `/upload/**` 用于本地文件查看，需要保留，但应避免让非法文件进入可访问目录。

### 2.3 路径安全校验不足

`extension` 来自客户端文件名，虽然最终使用了 `getCanonicalFile()`，但没有校验最终目标路径是否仍位于上传根目录内。

影响：

- 特殊文件名可能导致路径逃逸风险。
- 存储层缺少最后一道防线。

### 2.4 文件落盘和数据库入库缺少补偿

当前流程是先 `transferTo(dest)` 写入磁盘，再 `save(documentInfo)` 入库。如果数据库保存失败，会留下孤儿文件。

影响：

- 上传目录堆积无主文件。
- 后续清理、审计和容量统计困难。
- 上传接口返回失败，但磁盘状态已经改变。

### 2.5 上传访问地址和存储实现强耦合

`WebMvcConfig` 将 `./upload` 映射为 `/upload/**`，当前阶段允许通过该路径查看本地上传文件。后续文件会迁移到对象存储，因此上传服务不应把本地路径、访问 URL 和存储类型写死在实现类中。

影响：

- 后续迁移对象存储时需要改动业务代码。
- 本地 `fileUrl` 生成规则和未来对象存储 URL 规则难以统一。
- 当前 `storageType` 固定为 `local`，没有为对象存储预留扩展点。

### 2.6 原始文件名未清洗

`originFileName` 直接使用 `file.getOriginalFilename()`，没有做空值兜底、路径分隔符清洗和长度限制。

影响：

- 原始文件名为空时可能违反数据库 `not null` 约束。
- 超长文件名可能导致入库失败。
- 异常文件名可能污染展示层或日志。

### 2.7 普通上传接口缺少用户归属

`DocumentController.upload` 调用 `upload(file)`，最终写入 `userId = null`、`knowledgeId = null`。知识库上传入口已经做了用户和知识库校验，但普通上传接口仍会产生无归属文档。

影响：

- 后续如果进入检索链路，容易形成全局文档池。
- 难以做状态查询、删除权限判断和后续文档管理。

## 3. 修复目标

1. 上传阶段只允许合法 PDF 文件进入系统。
2. 存储文件名全局唯一，不发生覆盖。
3. 文件实际路径必须被限制在上传根目录内。
4. 文件落盘、数据库入库失败时有补偿。
5. 当前阶段保留 `/upload/**` 本地查看能力，但上传服务需要为后续对象存储迁移预留配置和抽象。
6. 文档必须有明确归属，至少绑定 `userId` 或 `knowledgeId`。
7. 文档处理状态真实表达解析结果。

## 4. 修复方案

### 4.1 新增上传配置

新增配置类：

- `UploadProperties`

建议配置：

```yaml
ai:
  upload:
    base-dir: ./upload
    url-prefix: /upload
    storage-type: local
    allowed-extensions:
      - .pdf
    allowed-content-types:
      - application/pdf
    max-origin-file-name-length: 255
    public-static-enabled: true
```

替换硬编码：

- `DocumentUploadServiceImpl.UPLOAD_DIR`
- `DocumentUploadServiceImpl.URL_PREFIX`
- `WebMvcConfig.UPLOAD_DIR`
- `WebMvcConfig.URL_PREFIX`

验收标准：

- 上传目录和访问前缀由配置控制。
- 当前本地上传文件仍可通过 `/upload/{date}/{filename}` 查看。
- `storageType` 可通过配置控制，后续能扩展为对象存储。
- 测试环境可以单独指定临时上传目录。

### 4.2 文件名改为 UUID

将当前文件名生成逻辑：

```java
String newFilename = System.currentTimeMillis() + extension;
```

改为：

```java
String newFilename = UUID.randomUUID() + extension;
```

或：

```java
String newFilename = "doc_" + UUID.randomUUID() + extension;
```

验收标准：

- 并发上传 100 个同名 PDF，不产生覆盖。
- 数据库 `file_name` 与磁盘文件一一对应。

### 4.3 增加 PDF 白名单校验

上传阶段执行三层校验：

1. 扩展名必须是 `.pdf`。
2. `Content-Type` 必须是 `application/pdf`。
3. 文件头必须以 `%PDF-` 开头。

非法文件直接抛出业务异常：

- 不创建文件。
- 不写入数据库。
- 不触发异步处理。

验收标准：

- `.exe`、`.txt`、伪装成 `.pdf` 的文本文件都不能上传成功。
- 合法 PDF 上传后进入 `pending` 状态。

### 4.4 清洗原始文件名

新增原始文件名规范化逻辑：

1. 为空时使用默认名，例如 `unnamed.pdf`。
2. 去除路径分隔符和控制字符。
3. 限制长度不超过数据库字段长度。
4. 原始文件名只用于展示，不参与存储路径拼接。

验收标准：

- 空文件名不会导致数据库异常。
- 超长文件名会被安全截断。
- `origin_file_name` 不包含路径分隔符。

### 4.5 增加路径边界校验

计算目标文件路径后，必须校验：

```java
Path root = uploadBaseDir.toPath().toRealPath();
Path dest = root.resolve(datePath).resolve(newFilename).normalize();
if (!dest.startsWith(root)) {
    throw new BusinessException("非法文件路径");
}
```

验收标准：

- 构造特殊文件名无法写出上传根目录。
- 所有落盘文件都位于配置的 `base-dir` 内。

### 4.6 增加失败补偿

建议采用以下流程：

1. 完成文件合法性校验。
2. 创建上传目录。
3. 写入文件。
4. 保存 `document_info`。
5. 触发异步处理。

如果第 4 步入库失败，需要删除第 3 步已写入的文件。

如果第 5 步触发失败，文档记录应标记为 `failed`，并记录 `process_error`。

验收标准：

- 模拟数据库保存失败，不留下孤儿文件。
- 模拟异步触发失败，文档状态不是永久 `pending`。

### 4.7 调整非 PDF 状态语义

如果上传阶段已经限制只允许 PDF，`DocumentProcessServiceImpl` 中非 PDF 分支理论上不会再出现。

保留防御逻辑时，不应标记为 `completed`，建议改为 `failed`：

```java
markFailed(documentInfo, "不支持的文件类型: " + fileType);
```

验收标准：

- 非支持类型不会显示为处理成功。
- 状态语义与实际可检索能力一致。

### 4.8 保留本地查看能力，预留对象存储迁移

当前阶段暂不做上传文件鉴权，继续保留 `/upload/**` 静态映射，确保上传结果里的 `fileUrl` 可以直接查看本地文件。

本阶段重点是把本地存储细节从业务代码中抽出来，为后续对象存储迁移做准备：

1. `base-dir`、`url-prefix`、`storage-type` 进入配置。
2. `fileUrl` 通过统一方法生成，不在业务流程中拼接硬编码路径。
3. `storagePath` 保存本地绝对路径；后续对象存储时可保存 object key。
4. `storageType` 当前为 `local`；后续可扩展为 `oss`、`s3`、`minio` 等。
5. `WebMvcConfig` 的 `/upload/**` 映射由 `public-static-enabled` 控制，当前默认开启。

后续对象存储迁移建议：

1. 新增 `DocumentStorageService` 接口。
2. 提供 `LocalDocumentStorageService` 实现。
3. 再新增对象存储实现，例如 `ObjectDocumentStorageService`。
4. 上传服务只依赖 `DocumentStorageService`，不关心文件写到本地还是对象存储。

验收标准：

- 本地上传后返回的 `fileUrl` 可以直接访问。
- 上传服务不再硬编码 `./upload`、`/upload` 和 `local`。
- 关闭 `public-static-enabled` 后不会注册 `/upload/**` 静态映射。
- 后续增加对象存储实现时，不需要重写上传业务主流程。

### 4.9 明确普通上传接口归属策略

建议二选一：

方案 A：废弃普通 `/document/upload`，统一使用知识库上传。

方案 B：普通上传接口也必须传入 `userId`，并写入 `document_info.user_id`。

短期推荐方案 B：

```http
POST /document/upload?userId={userId}
```

验收标准：

- 新上传文档不再出现 `user_id = null`。
- 状态查询和删除可以基于 `userId` 做归属判断。

## 5. 实施步骤

### 阶段一：安全底线修复

1. 新增 `UploadProperties`。
2. 将上传目录、URL 前缀从硬编码迁移到配置。
3. 文件名改为 UUID。
4. 增加扩展名、`Content-Type`、PDF 文件头校验。
5. 清洗并截断原始文件名。
6. 增加目标路径边界校验。

### 阶段二：一致性修复

1. 上传入库失败时删除已落盘文件。
2. 异步处理触发失败时标记文档 `failed`。
3. `DocumentProcessServiceImpl` 非 PDF 分支改为 `failed`。
4. 空文本 PDF 标记为 `failed` 或新增 `completed_empty`。

### 阶段三：本地查看与对象存储预留

1. 保留当前 `/upload/**` 本地静态访问能力。
2. 将 `/upload/**` 是否开启做成配置项，当前默认开启。
3. 抽出 `fileUrl` 生成逻辑，避免散落硬编码。
4. 普通上传接口补充 `userId`。
5. 为后续 `DocumentStorageService` 抽象和对象存储实现预留字段语义。

### 阶段四：测试补齐

新增或补充测试：

- 空文件上传失败。
- 非 PDF 上传失败。
- 伪 PDF 上传失败。
- UUID 文件名并发不冲突。
- DB 保存失败后文件被清理。
- 非 PDF 处理不会标记 `completed`。
- 本地上传后 `fileUrl` 可访问。
- 关闭静态映射配置后 `/upload/**` 不再注册。

## 6. 推荐优先级

| 优先级 | 修复项 | 原因 |
| --- | --- | --- |
| P0 | 文件类型白名单 | 阻止非法文件进入系统 |
| P0 | 普通上传补充用户归属 | 防止无主文档进入检索链路 |
| P1 | UUID 文件名 | 防止并发覆盖 |
| P1 | 路径边界校验 | 防止路径逃逸 |
| P1 | 入库失败补偿 | 防止孤儿文件 |
| P1 | 上传配置化和本地访问 URL 统一生成 | 保留当前查看能力，并为对象存储迁移做准备 |
| P2 | 空文本状态、失败重试、批量结果 | 提升体验和可维护性 |

## 7. 验收清单

- [ ] 空文件不能上传。
- [ ] 非 PDF 文件不能上传。
- [ ] 伪 PDF 文件不能上传。
- [ ] 同一毫秒并发上传不会覆盖文件。
- [ ] 文件实际路径不能逃出上传根目录。
- [ ] 原始文件名为空或超长时不会导致入库失败。
- [ ] 数据库保存失败后不会留下孤儿文件。
- [ ] 非 PDF 不会被标记为 `completed`。
- [ ] 上传后返回的 `fileUrl` 可以查看本地文件。
- [ ] 上传目录、访问前缀、存储类型不再硬编码在上传实现中。
- [ ] 普通上传文档有明确 `userId`。
- [ ] 知识库上传仍校验用户与知识库归属。
- [ ] 处理失败时 `process_status = failed` 且 `process_error` 可读。

## 8. 后续建议

1. 将上传状态查询、删除、重试能力补齐。
2. 给 `document_info` 增加 `chunk_count`、`vector_count`、`processed_at` 字段。
3. 删除文档时同步删除对应 chunk 和向量。
4. 将 API Key 从 `application.yaml` 迁移到环境变量，并轮换已暴露密钥。
5. 在前端展示上传中、处理中、完成、失败四类状态。
6. 后续接入对象存储时，通过 `DocumentStorageService` 新增实现，不重写上传业务主流程。
