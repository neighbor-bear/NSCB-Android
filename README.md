# NSCB-Android
一款基于NSCB移植的Switch ROM安卓版格式转换工具
## 1. 项目简介

NSC_BUILDER 原项目由 Python 脚本 + Windows 批处理构成（约 9.5 万行），在 Android 上运行需要 Termux/Python 或模拟器。本项目不采用套壳方案，而是：

- 用 **Java** 重新实现 NSCB 的容器解析、加密、解压、打包、整合逻辑；
- 用 **Android SAF**（Storage Access Framework）读写用户选择的文件/目录，无需存储权限；
- 用 **zstd-jni** 处理 NSZ/NCZ 的 zstd 压缩流；
- 保留 NSCB 的命名规则与输出结构（`TitleID [v版本]` 等）。

可处理：

| 输入 | 输出 | 功能 |
|---|---|---|
| `.nsz` | `.nsp` | NCZ → NCA 解压、重建 PFS0 |
| `.nsp` | `.xci` | 创建游戏卡镜像（secure 分区） |
| `.xci/.xcz` + 更新 `.nsp` | `.xci` | update 分区整合 / 真合并 |
| `.nsp` | `.nsz` | 逐 NCA zstd 压缩 |
| `.xcz` | `.xci` | 解压 + 重建 |
| 任意容器 | 目录 | 解包（可按分区） |
| `.xci/.xcz` | `.xci` | 裁剪（保留 update + secure） |
| 任意容器 | 分卷 | FAT32 4GB 拆分 / 合并 |
| `.nca` 集合 | `.nsp` / `.xci` | 打包 / 创建 |
| Program NCA | 目录 | RomFS 提取 |

> 合规声明：本项目不内置任何密钥、不内置任何游戏内容，只处理用户自行提供的文件。请遵守所在地法律与任天堂服务条款。

---

## 2. 设计目标与边界

**目标**

1. 纯原生：APK 内只有 DEX + zstd 原生库，无解释器、无 Linux 用户态。
2. 流式处理：大文件（几 GB）不整体读入内存，按块读写。
3. 可移植：引擎层（`engine/`）不依赖 Android API，可在桌面 JVM 上测试。
4. 交互简单：SAF 选目录 → 应用内过滤选择器 → 输出目录 → 后台任务 → 进度/日志。

**明确不做**

- 不实现 NSP 安装到主机、不实现 USB/MTP 传输；
- 不实现联网下载、数据库刮削；
- 不内置 prod.keys；
- 不实现 Nintendo 官方签名校验（仅做结构/哈希自校验，与 NSCB 的“校验”含义一致）。

---

## 3. 目录结构

```
nscb-android/
├── app/
│   ├── AndroidManifest.xml
│   ├── res/                         # 图标与自适应图标资源
│   │   ├── values/colors.xml
│   │   ├── mipmap-*/ic_launcher*.png
│   │   ├── mipmap-anydpi-v26/ic_launcher*.xml
│   │   └── drawable-nodpi/ic_launcher_foreground.png
│   └── src/io/nscb/app/
│       ├── MainActivity.java        # 主界面 + 任务编排 + 选择器
│       ├── SettingsActivity.java    # 设置：prod.keys 导入/移除
│       ├── SafSource.java           # SAF Uri → ByteSource
│       └── SafSink.java             # SAF Tree Uri → Sink
├── engine/io/nscb/engine/           # 纯 Java 引擎（无 Android 依赖）
│   ├── Aes.java                     # AES-ECB / CTR / XTS
│   ├── Keys.java                    # prod.keys 解析与 key 派生
│   ├── ByteSource.java              # 随机读取抽象
│   ├── FileChannelSource.java       # 桌面/文件实现
│   ├── SliceSource.java             # 子区间视图
│   ├── MemorySource.java            # 内存实现
│   ├── Sink.java                    # 输出抽象
│   ├── FolderSink.java              # 目录输出（桌面测试）
│   ├── Container.java               # PFS0 / HFS0 解析与生成
│   ├── Nca.java                     # NCA 头/分区/文件读取
│   ├── Cnmt.java                    # CNMT 元数据
│   ├── Nacp.java                    # NACP 元数据
│   ├── Ticket.java                  # ticket（.tik）解析
│   ├── Archive.java                 # 容器统一入口 + Report + 校验
│   ├── Report.java                  # 信息报告模型
│   ├── Nsz.java                     # NCZ 解压 / NSZ 压缩
│   ├── Xci.java                     # XCI 创建 / 重建 / 整合 / 真合并
│   ├── Packer.java                  # PFS0 打包与顺序
│   ├── Romfs.java                   # RomFS (IVFC) 提取
│   ├── Split.java                   # 分卷拆分/合并
│   ├── Renamer.java                 # 输出命名
│   ├── Util.java                    # 读取工具、大小、nonce
│   └── Progress.java                # 进度/日志回调

```

---

## 4. 构建与环境

### 4.1 依赖

| 依赖 | 说明 |
|---|---|
| JDK 11 | 编译 `-source 8 -target 8`，避免 d8 处理过新的 class 文件 |
| Android SDK Platform 34 | `android.jar` |
| Android Build-Tools 34.0.0 | `aapt2` / `d8` / `zipalign` / `apksigner` |
| zstd-jni 1.5.7-2 AAR | Java 接口 + 4 个 ABI 的 `libzstd-jni.so` |


```


### 4.2 手动构建要点

```bash
SDK=xxx/android-sdk
BT=$SDK/build-tools/34.0.0
PLATFORM=$SDK/platforms/android-34/android.jar

find engine app/src -name '*.java' > sources.txt
javac -source 8 -target 8 -encoding UTF-8 -nowarn \
  -cp "$PLATFORM:zstd/classes.jar" -d build/classes @sources.txt
( cd build/classes && jar cf ../engine.jar . )
$BT/d8 --release --min-api 24 --lib "$PLATFORM" \
  --output build/dex build/engine.jar zstd/classes.jar
$BT/aapt2 compile --dir app/res -o build/res.zip
$BT/aapt2 link -o build/base.apk --manifest app/AndroidManifest.xml \
  -I "$PLATFORM" --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 2 --version-name 1.1 build/res.zip
```

### 4.4 桌面回归测试

引擎不依赖 Android，可直接在 JVM 上跑：

```bash
python3 tests/gen_test.py            # 生成 /tmp/nscb-test 夹具
mkdir -p /tmp/tc
find engine tests -name '*.java' > /tmp/srcs.txt
javac -nowarn -encoding UTF-8 -cp /tmp/zstd-jni.jar -d /tmp/tc @/tmp/srcs.txt
java -cp /tmp/tc:/tmp/zstd-jni.jar io.nscb.test.TestMain
# 期望输出：ALL TESTS PASSED
```

> `/tmp/zstd-jni.jar` 是包含桌面 linux 原生库的 zstd-jni jar；Android 构建使用 AAR。

---

## 5. 总体架构

```
┌────────────────────────────────────────────────────────────┐
│                        MainActivity                        │
│  自绘 UI（标题/状态卡/分区操作/日志） · 任务线程池 · 进度条   │
│  文件选择：系统 SAF 多选 / 应用内后缀过滤选择器              │
└───────────────┬────────────────────────────┬───────────────┘
                │                            │
        SafSource / SafSink            SettingsActivity
        （ContentResolver）            （prod.keys 管理）
                │                            │
┌───────────────▼────────────────────────────▼───────────────┐
│                       engine (纯 Java)                     │
│  Archive ── Container(PFS0/HFS0) ── Nca ── Cnmt/Nacp/Ticket│
│     │            │                    │                    │
│     ├── Nsz (NCZ/NCZBLOCK, zstd)      │                    │
│     ├── Xci (create/rebuild/integrate/merge)                │
│     ├── Packer (PFS0 writer)          │                    │
│     ├── Romfs (IVFC)                  │                    │
│     ├── Split (FAT32 volumes)         │                    │
│     └── Keys / Aes (ECB/CTR/XTS)      ▼                    │
└────────────────────────────────────────────────────────────┘
```

分层原则：

- `engine` 只依赖 `java.*` 与 `zstd-jni`，便于桌面测试；
- `app` 只做 I/O 适配（SAF）、UI、任务编排；
- 所有耗时任务在单线程 `ExecutorService` 中串行执行，UI 通过 `Handler` 回投。

---

## 6. 数据格式

### 6.1 容器识别（Archive.open）

按文件头判断，不信任扩展名（扩展名只用于区分 NSZ/XCZ）：

| 检测位置 | 魔数 | 类型 |
|---|---|---|
| 0x00 | `PFS0` | `.nsz` → NSZ，否则 NSP |
| 0x100 | `HEAD` | `.xcz` → XCZ，否则 XCI |
| 0x200 | `NCA3/NCA2/NCA0` 或扩展名 `.nca/.ncz` | 独立 NCA |

识别后：

- NSP/NSZ：解析 PFS0，收集 NCA 条目、ticket；
- XCI/XCZ：读取 0x200 头 → 解析 root HFS0 → 取 `secure` / `update` / `normal` 子分区；
- NCA：直接构造 `Nca`。

最后 `resolveMetadata()` 从内容 NCA 中找到 CNMT / Control NCA，填充 `Report`。

### 6.2 PFS0（NSP）

- 头 0x10：`PFS0`、entryCount(u32)、stringTableSize(u32)、保留。
- 条目 0x18：`offset(u64)`、`size(u64)`、`nameOffset(u32)`、保留(u32)。
- 数据区从 `headerSize = 0x10 + n*0x18 + strTab` 开始。
- 生成时按 NSCB 规则补 0x10 对齐（`Container.buildPfs0Header`）。

### 6.3 HFS0（XCI 分区）

- 条目 0x40：`offset(u64)`、`size(u64)`、`nameOffset(u32)`、`hashRegion(u32)`、`sha256(first 0x200)(32B)`。
- Header 保证 0x200 对齐。
- XCI secure 分区的条目一般是 NCA / `.tik` / `.cert` / `.xml`。

### 6.4 XCI / XCZ 头部（0x200）

| 偏移 | 字段 | 本项目用途 |
|---|---|---|
| 0x100 | `HEAD` | 识别 |
| 0x104 | secure 分区偏移（扇区，0x200） | 重建时刷新 |
| 0x108 | backup 分区偏移 | 保留 |
| 0x10C~0x10F | titleKekIndex / gamecardSize / headerVersion / flags | 保留 |
| 0x110 | packageId | 保留 |
| 0x118 | 卡带容量（扇区数，非字节） | 重建时刷新 |
| 0x130 | HFS0 根偏移 | 保留/写入 |
| 0x138 | HFS0 根大小 | 重建时刷新 |
| 0x140 | 根 HFS0 头 sha256 | 重建时刷新 |
| 0x180/0x184/0x188 | secureMode / titleKeyFlag / keyFlag | 保留 |
| 0x190 | firmwareVersion（gamecard info） | 保留 |

> 工具显示的游戏“版本”来自 secure 内 NACP/CNMT，而不是 0x190，因此真合并通过替换 secure 内容实现版本更新。

### 6.5 NCA

头部 0xC00，AES-XTS（`header_key`，sector 0x200）加密；若文件头本身明文（未加密 NCA），则直接使用。

关键字段（解密后）：

| 偏移 | 字段 |
|---|---|
| 0x200 | magic `NCA3/NCA2/NCA0` |
| 0x204 | isGameCard |
| 0x205 | contentType（0=Program,1=Meta,2=Control,3=Manual,4=Data,5=PublicData） |
| 0x206 | cryptoType（主键版本，1 起） |
| 0x207 | keyIndex（0=application, 1=ocean, 2=system） |
| 0x208 | ncaSize |
| 0x210 | titleId |
| 0x218 | contentIndex |
| 0x21C | sdkVersion |
| 0x220 | cryptoType2 |
| 0x230 | rightsId（16B） |
| 0x240 | 4 个 section 的 media offset/end（0x200 单位） |
| 0x300 | key area（0x40，AES-ECB 由 areaKey 解密，4×16B 区密钥） |
| 0x400 | 4 个 section 头（0x200 每个） |

Section 头内：

- `+0x03` fsType
- `+0x04` cryptoType
- `+0x40` sectionStart（NCA 内偏移）
- `+0x48` payloadSize
- `+0x140` ctrValue（AES-CTR 起始块）

`Nca` 通过 `sectionSource(i)` 返回该 section 的解密流：

```java
// 摘要：按绝对偏移驱动 CTR，nonce = ctrNonce(block)
long block = (absoluteOffset >> 4);
Aes.Ctr ctr = new Aes.Ctr(titleKey, Util.ctrNonce(block));
```

### 6.6 CNMT / NACP / Ticket

- **CNMT**：`Cnmt.parse` 读取 titleId、version(u32)、titleType(u8)、contents 列表（每项 hash32 + ncaId + size48）。
  - type：`5=Application`，`6=Patch`，`7=AddOnContent` 等，`Cnmt.typeName` 输出可读名称。
- **NACP**：`Nacp.parse` 读取 16 种语言的名称/发行商、`displayVersion`（0x3060 起）、图标文件名等。
- **Ticket**：`Ticket.parse` 解析 `.tik`，`rightsId`、`encTitleKey`；若已导入 `titlekek` 可解出 title key。

### 6.7 NSZ / NCZ / NCZBLOCK

NSZ 是 PFS0，条目名以 `.ncz` 结尾。NCZ 结构：

```
0x0000  加密 NCA 头 (0x4000 = 16 个 sector)
0x4000  "NCZSECTN"
0x4008  count (u64 LE)
0x4010  section 表 count * 0x40：
          offset(u64)   —— NCA 绝对偏移（含 0x4000）
          size(u64)
          cryptoType(u64)
          reserved(u64)
          key(16)
          counter(16)
之后    zstd 单帧压缩体（一般）
```

新版 NSZ 可能使用块压缩：

```
"NCZBLOCK"
+0x08 version(u8)
+0x09 type(u8)
+0x0A unused(u8)
+0x0B blockSizeExponent(u8)   // blockSize = 1 << exp
+0x0C numberOfBlocks(u32)
+0x10 decompressedSize(u64)
+0x18 各块压缩长度 u32[nBlocks]
之后   各块数据；若压缩长度 >= 解压长度则原样存储
```

解压后得到**明文 NCA**（0x4000 之后的内容），再按 section 表对 `cryptoType 3/4` 的区段用 `Aes.Ctr(key, counter)` 重新加密，写回为普通 `.nca`。

**未压缩大小推导**（官方 nsz 4.6.1 一致）：

```java
ncaSize = max(0x4000 + Σ section.size, max(section.offset + section.size))
```

- 块压缩时：`ncaSize = 0x4000 + decompressedSize`。
- 若已导入 `header_key`，可用 NCA 头 0x208 的 `ncaSize` 覆盖。
- 段数上限按 `(entrySize - 0x4010) / 0x40` 推导，避免误判；同时设 0x10000 总上限。

---

## 7. 加密体系

### 7.1 prod.keys 解析

`Keys.fromStream` 逐行匹配 `name = hex`，键名小写；`master_key_XX` 统一为两位十六进制（`master_key_0A` 等）。

兼容规则：若存在 `master_key_16` 而不存在 `master_key_10`，把 `master_key_16` 映射为 `master_key_10`（部分 keys 文件的旧命名）。

### 7.2 派生

```java
titlekek[i] = AES-ECB-decrypt(master_key_00..19[i], titlekek_source)
kek        = AES-ECB-decrypt(master_key, aes_kek_generation_source)
srcKek     = AES-ECB-decrypt(kek, <app|ocean|system>_source)
areaKey    = AES-ECB-decrypt(srcKek, aes_key_generation_source)
```

与 NSCB `generateKek()` 的三次 ECB 解密顺序保持一致。

### 7.3 AES 实现

`Aes.java` 基于 JCE：

- **ECB**：`Cipher.getInstance("AES/ECB/NoPadding")`，单块/多块。
- **CTR**：`AES/CTR/NoPadding`，自行维护计数器；`Aes.Ctr.process(absoluteOffset, data, off, len)` 允许任意位置读写。
- **XTS**：`AES/XTS/NoPadding`（Android API 24 支持），sector 0x200；用于 NCA 头。

### 7.4 CTR nonce 约定（关键）

Switch NCA 的 CTR 计数器是 **128 位大端，但有效值放在前 8 字节**：

```java
public static byte[] ctrNonce(long v) {
    byte[] n = new byte[16];
    // n[0..7] = big-endian v, n[8..15] = 0
}
```

**不是**放在 `[8..15]`（常见错误会导致解密出乱码）。`Nca.CryptSectionSource` 与 `Nsz.encryptChunk` 均使用该约定：

```java
long block = absoluteOffset >> 4;
new Aes.Ctr(key, Util.ctrNonce(block))
```

### 7.5 title key 获取路径

1. `rightsId == 0` → 使用 key area 第 2 区密钥（`areaKeys[2]`），无需 ticket；
2. 有 ticket 且可解 → 使用 ticket 的 title key；
3. 有 ticket 的 `encTitleKey` + `titlekek` → `AES-ECB-decrypt(titlekek, encTitleKey)`；
4. 都没有 → 标记“title key unavailable”，只能读未加密区/元数据。

---

## 8. 引擎模块 API

| 类 | 职责 | 关键 API |
|---|---|---|
| `ByteSource` | 随机读取抽象（`size()` / `read(pos,...)`） | 被所有解析器使用 |
| `FileChannelSource` | 桌面文件实现 | `new FileChannelSource(FileChannel)` |
| `SliceSource` | 把大容器某段映射为独立源 | `new SliceSource(src, pos, len)` |
| `MemorySource` | 内存字节数组 | `new MemorySource(byte[])` |
| `Sink` | 输出抽象（`open(relativePath)`） | `FolderSink` / `SafSink` |
| `Progress` | 进度与日志回调 | `onProgress(msg, done, total)`、`onLog` |
| `Container` | PFS0/HFS0 解析与生成 | `parse`、`buildPfs0Header`、`buildHfs0Header` |
| `Nca` | NCA 头、key area、section、内部 PFS0 | `sectionSource`、`readNacp/readCnmt/readIcon`、`extractFile` |
| `Cnmt` | CNMT 解析 | `parse`、`typeName` |
| `Nacp` | NACP 解析 | `parse` |
| `Ticket` | ticket 解析 | `parse` |
| `Archive` | 容器统一入口 | `open(src,name,keys)`、`report()`、`verify()`、`extract(sink,...)`、`programNca()` |
| `Report` | 信息模型 | 字段 + `render()` |
| `Keys` | 密钥/派生 | `fromFile`、`areaKey`、`titleKek`、`decryptTitleKey` |
| `Aes` | 加密原语 | `ecbEncrypt/ecbDecrypt`、`Ctr`、`Xts` |
| `Nsz` | NSZ/NCZ | `readInfo`、`decompress`、`toNsp`、`toNsz`、`cnmtSize` |
| `Xci` | XCI 重建 | `create`、`fromArchive`、`rebuild`、`integrateUpdate`、`mergeUpdate` |
| `Packer` | PFS0 打包 | `packNsp`、`order` |
| `Romfs` | RomFS/IVFC 提取 | `extract(nca, sink, prefix, p)` |
| `Split` | 分卷 | `split`、`merge`、`sortPartNames`、`FAT32_BLOCK` |
| `Renamer` | 输出命名 | `suggest(report, fallback, ext)` |
| `Util` | 工具 | `ctrNonce`、`size`、`hex/unhex`、`sanitize`、`copyRange` |

---

## 9. 应用层

### 9.1 MainActivity

职责：

- 自绘 UI（无 XML 布局）；
- 维护 `selected`（归档多选）与 `pendingFiles`（任务输入）；
- 通过 `startActivityForResult` 调用 SAF；
- 把任务提交到单线程 `ExecutorService`；
- 通过 `Handler` 更新状态、进度条、日志。

状态字段：

```java
private boolean busy;                 // 任务占位
private char pendingTreeAction;       // 输出目录后要执行的动作
private boolean treePickerActive;     // 当前等待的是输出目录
private char pendingFileAction;       // 文件选完后要执行的动作
private int pendingTreeReq;           // 输出目录使用的 requestCode
private String[] pendingExts;         // 应用内选择器的后缀过滤
private final List<Uri> pendingFiles; // 已选输入文件
```

动作编码（`runWithTree`）：

| 码 | 动作 |
|---|---|
| `E` | 解包 |
| `C` | NSZ → NSP |
| `B` | NSP → XCI |
| `U` | XCI + NSP（update 分区） |
| `V` | XCI 真合并 |
| `G` | 整合为 NSP |
| `Z` | NSP → NSZ |
| `X` | XCZ → XCI |
| `R` | 提取 RomFS |
| `I` | 从 NCA 创建 XCI |
| `T` | 裁剪 XCI |
| `P` | NCA 打包 NSP |
| `S` | 拆分 |
| `M` | 合并 |

### 9.2 SAF 适配

- `SafSource`：`ContentResolver.openAssetFileDescriptor` / `openInputStream`，实现 `ByteSource`；长文件用 `FileChannel`（通过 `ParcelFileDescriptor`）以获得随机访问与较好性能。
- `SafSink`：以用户选择的 **tree URI** 为根，`DocumentsContract.createDocument` 建文件，`openOutputStream` 写入；支持 `a/b/c` 子路径自动建目录。
- 权限：`ACTION_OPEN_DOCUMENT_TREE` 申请读写并 `takePersistableUriPermission`，避免重启后失效。

> 注意：`SafSink` 的父目录必须用 **tree URI 的 document id** 构造（`buildDocumentUriUsingTree`），不要直接使用 `parentDocumentUri`，否则在部分文件管理器上会创建失败。

### 9.3 输入选择器

系统 SAF 只能按 MIME 过滤，无法按扩展名过滤，因此转换类任务使用**应用内选择器**：

1. 先让用户选输入文件夹（`ACTION_OPEN_DOCUMENT_TREE`，`REQ_INPUT_TREE`）；
2. `onActivityResult` 保存该 tree 权限，调用 `showDocPicker(tree, exts, done)`；
3. 弹窗内用 `DocumentsContract.buildChildDocumentsUriUsingTree` 查询子项，读取 `DISPLAY_NAME / DOCUMENT_ID / MIME_TYPE / SIZE`；
4. 目录始终显示、可进入（栈式“上一级”），文件仅显示匹配后缀（`matchesExt`）；
5. 多选用 `☐/☑` 前缀，按 DOCUMENT_ID 去重；
6. 确定后回调 `done.onPicked(files)`，再走“选择输出目录”流程。

过滤配置：

- `NSZ → NSP`：`{".nsz"}`
- `NSP → XCI`：`{".nsp"}`
- 整合、打包、拆分等：仍用系统 `ACTION_OPEN_DOCUMENT` 多选，显示全部文件。

### 9.4 SettingsActivity

- 显示密钥状态：未导入 / 已导入 N 条 / 缺少 `header_key` / 读取失败；
- **导入**：先写 `prod.keys.tmp`，用 `Keys.fromFile` 校验，成功后 rename 为 `prod.keys`；失败删除临时文件；
- **移除**：删除私有目录中的 `prod.keys`；
- 密钥存于 `getFilesDir()/prod.keys`，不随 APK 分发、不上传网络；
- 主界面 `onResume` 会重新加载密钥并刷新状态 chip。

### 9.5 线程与进度

```java
private void runTask(String label, Task task) {
    busy = true;
    ui.post(() -> busyBar.setVisibility(View.VISIBLE));
    worker.execute(() -> {
        try { task.run(); }
        catch (Throwable t) { ui.post(() -> showError(t)); }
        finally { ui.post(() -> { busy = false; busyBar.setVisibility(View.INVISIBLE); }); }
    });
}
```

`Progress` 在引擎内回调 `onProgress(message, done, total)`；UI 节流到约 200ms 更新一次状态文本；日志用 `StringBuilder` 缓冲（上限约 200 KB）。

### 9.6 UI 设计

- 深色背景 `#0A0E13`，卡片 `#141A22` / 条目 `#1A212B`，描边 `#26303E`；
- 强调色 `#58A6FF`；
- 标题区：`NSCB PORT` + 副标题 + “设置”chip；
- 状态卡：状态文本 + 密钥状态 chip + 不确定进度条；
- 中间可滚动分区：归档 / 转换 / 整合与打包 / 工具；
- 底部固定高度日志卡（190dp），带“清空”；
- 按钮使用 `GradientDrawable` + `RippleDrawable` 实现圆角与波纹。

---

## 10. 功能实现细节

### 10.1 NSZ → NSP

入口：`Nsz.toNsp(Archive a, Sink sink, String outName, Progress p)`。

1. 遍历 `a.pfs.entries`；
2. `.ncz` 条目：`Nsz.readInfo` 得到 `ncaSize`、section 表、块压缩信息；
3. `Nsz.decompress` 以 `ZstdInputStreamNoFinalizer`（或 `BlockInputStream`）顺序解压；
4. 按 section 表在绝对偏移处把 `cryptoType 3/4` 区段用 `Aes.Ctr(sec.key, sec.counter)` **重新加密**；
5. 条目改名为 `.nca`，重建 PFS0 头并写出；
6. 非压缩条目原样复制。

关键点：**解压后必须重新加密这些区段**，否则得到的是“解密后的 NCA”，与官方 NSZ 转出的 NCA 不一致。

### 10.2 NSP → XCI

入口：`Xci.fromArchive(a, outName, sink, p)` → `Xci.create(items, keys, ...)`。

1. 收集 NSP 中的 `.nca/.tik/.cert/.xml`；
2. 用 `Packer.order` 按 CNMT 顺序排列（meta → program → control → …）；
3. 构建 secure HFS0（条目哈希 = 前 0x200 字节 sha256）；
4. 构造空 `update` / `normal` / `logo` 分区；
5. 写 0xF000 头：`HEAD`、secure 偏移（扇区）、卡带容量（扇区数-1）、根 HFS0 偏移/大小/哈希；
6. 依次写 root、空分区、secure 头与 NCA 数据、logo。

### 10.3 XCI + NSP（update 分区）

入口：`Xci.integrateUpdate`。保留 secure（基底）不变，把更新 NSP 的条目构建成 HFS0 覆盖 root 的 `update` 分区，并刷新 XCI 头。

**局限**：多数查看工具读 secure 内的 NACP/CNMT，因此仍显示基底版本；只有主机运行时会读取 update 分区应用补丁。

### 10.4 真合并（版本生效）

入口：`Xci.mergeUpdate`（UI 动作 `V`）。

与 10.3 的差别：

- **secure 分区整体由更新 NSP 重建**（Program / Control / Meta / CNMT / ticket / cert）；
- `update` 分区写成空 HFS0；
- 重新计算 secure 偏移、卡带容量、root HFS0 哈希，并保留 0xF000 头的其余字段。

结果：

- 查看工具读取 secure 的 NACP/CNMT → 显示更新版本（例如 1.0.1）；
- 主机直接启动到更新内容；
- 不再同时保留本体与补丁两份内容，体积不会异常膨胀。

代码要点：`write(...)` 增加 `mergeSecure` 参数，secure 条目由通用 `collectItems(arc, entries, ...)` 收集；更新包条目若为 `.ncz` 会先解压。

### 10.5 NSP → NSZ

入口：`Nsz.toNsz(a, sink, outName, level, tempDir, p)`。

1. 对每个 `.nca` 分配 `NCZ` 的 section 表（cryptoType 3/4 的区段记录 key/counter）；
2. 以原始加密 NCA 头 0x4000 原样写入；
3. 写 `NCZSECTN` 表；
4. 用 `ZstdOutputStream` 压缩 0x4000 之后的内容（默认 level 17）；
5. 非 NCA 条目原样写入。

### 10.6 XCZ → XCI

入口：`Xci.rebuild(a, keepAll=true, decompress=true, ...)`。对 secure 内 `.ncz` 逐个解压并重新加密，重建 HFS0 与 XCI 头。

### 10.7 解包与 RomFS

- `Archive.extract(sink, allPartitions, p)`：按分区（`root/update/normal/secure/logo` 或 NSP 根）导出 NCA/tik/cert/xml；可选把 NCA 内部 PFS0 文件一并解出。
- `Romfs.extract(nca, sink, prefix, p)`：定位 Program NCA 的 IVFC 区段，解析 RomFS 目录树，按相对路径写入 `Sink`。

### 10.8 打包、拆分、合并

- `Packer.packNsp`：按 `Packer.order` 生成 PFS0，写 index 表与字符串表，数据区按 0x10 对齐；
- `Split.split`：以 `FAT32_BLOCK`（4GB 减安全余量）为块，输出 `name.00`、`name.01`…；`merge` 按序拼接；`sortPartNames` 同时支持数字与字母后缀。

---

## 11. 测试

`tests/gen_test.py` 合成以下夹具（`/tmp/nscb-test`）：

- `test.nsp`：Program + Control + Meta CNMT；
- `update.nsp`：Patch CNMT（type 6，version 0x10000）；
- `test.xci`：root(update/normal/secure) + secure HFS0；
- `program.nca`、`meta.cnmt.nca`、`control.nca`；
- `test.nsz` / `test.xcz`（不同压缩模式）；
- RomFS 期望目录；
- `prod.keys`（合成密钥）。

`tests/TestMain.java` 覆盖：

1. NSP 解析、信息报告、校验；
2. NCA 头解密与 PFS0 内容读取；
3. 解包 payload 一致性（sha256）；
4. 打包 NSP 回环；
5. NSZ 解压 / NCZ 段表 / 块压缩；
6. XCZ → XCI；
7. RomFS 提取与期望文件逐字节一致；
8. FAT32 拆分 / 合并；
9. NSP → XCI（secure 条目、payload 一致、头字段）；
10. XCI + update（update 分区替换、secure 不变）；
11. **XCI 真合并**（update 分区清空、secure 使用 patch CNMT、版本 0x10000、头哈希/容量/偏移正确、payload 一致）。

运行结果应为 `ALL TESTS PASSED`。

---

## 12. 已知限制

1. **工具显示版本 vs 运行版本**：只有真合并会让工具显示更新版本；传统 update 分区整合仍显示基底版本。
2. **真合并需要完整补丁 NSP**：更新包必须是 Patch（CNMT type 6）；DLC / 本体不能用于真合并。
3. **密钥依赖**：读取加密 NCA 头需要 `header_key`；读取 rightsId 内容的 title key 需要 `.tik` 或 `title.keys`。
4. **签名不被重算**：重建 XCI 只更新结构哈希（HFS0/root hash），不重签任天堂 RSA 签名；在 CFW/Atmos 环境下可运行，官方系统会拒绝。
5. **NSP 安装/传输未实现**：仅生成文件，不含 USB/MTP 推送。
6. **系统选择器无法按后缀过滤**：转换类任务改用应用内选择器；整合类仍显示全部文件。
7. **大文件性能**：受 SAF 与手机闪存限制；进度按块更新，临时解压会占用应用缓存目录空间。
8. **XCZ 的块压缩**：已支持 `NCZBLOCK`，但极端异常文件可能因尺寸推导失败而报错（需 `header_key` 或保留 CNMT）。

---

## 13. 二次开发指南

### 13.1 新增一个转换功能

1. 在 `engine` 新增/复用方法（例如 `Xci.trim`），保持 `ByteSource → Sink` 流式接口；
2. 在 `MainActivity.buildUi()` 中加 `action(...)`；
3. 在 `runWithTree` 增加动作码与 `runTask` 分支；
4. 输入过滤：转换类用 `pickInputTree(action, outReq, new String[]{".xxx"})`，整合类用 `pickFiles(...)`；
5. 输出命名统一走 `Renamer.suggest`；
6. 在 `tests/TestMain.java` 增加夹具与断言。

### 13.2 常见坑

- **CTR nonce**：值必须放 `[0..8]` 大端；放错会得到乱码 NCA。
- **HFS0 哈希区**：条目哈希是前 0x200 字节的 sha256，不是整个文件。
- **XCI 0x118**：单位是 0x200 扇区数，写字节数会让工具拒绝。
- **secure 偏移**：重建后必须同时刷新 0x104 与 root 中 secure 条目 size/hash。
- **SAF 父目录**：`createDocument` 的 parent 必须由 tree document id 构造。
- **d8 版本**：使用 JDK 11 输出 Java 8 字节码；JDK 21 的 class 文件会让 build-tools 34 的 d8 崩溃。
- **资源编译**：改 `app/res` 后必须重新执行 `aapt2 compile`，否则图标不更新。

### 13.3 扩展建议

- 增加 `title.keys` 导入（`Keys` 已支持 `titlekek`，只需补读取文件与 `decryptTitleKey` 调用）；
- 增加 NSP 内多 title 合并（DLC）时按 CNMT type 分组；
- 增加“保留基底 + 补丁”的混合合并（两份 NCA + 两个 ticket 同时进 secure），但体积会翻倍；
- 增加输出目录书签 / 最近任务历史（`SharedPreferences`）。

---

## 14. 版本历史

| 版本 | 内容 |
|---|---|
| 1.0 | 原生引擎、NSP/XCI/NCA/NSZ 读取、解包、打包、拆分合并、SAF UI、密钥导入 |
| 1.1 | UI 改版（卡片分区/状态卡/日志卡/波纹）、设置页密钥管理、转换任务后缀过滤选择器、NCZ 段数上限修复、XCI 真合并（版本生效）、应用图标与自适应图标 |

---

## 15. 免责声明

本项目仅用于学习文件格式与个人备份管理。请勿用于传播未经授权的游戏内容。任天堂相关格式、密钥派生规则的知识产权归其各自所有者。使用者需自行承担合规责任。
