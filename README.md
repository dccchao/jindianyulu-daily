# 每日阅读 · 在线书架

此仓库为 Android「毛选每日一句」提供 books.json 书目清单和每本书独立语录库。支持选书、离线缓存、每天固定一句、刷新换一句，桌面正文最多两行。

## 初次部署

1. 将部署包解压后的文件和目录上传到仓库根目录，保留 books、publisher、reading 和 .github/workflows 的层级。上传 .github 隐藏目录时，macOS Finder 可按 Command+Shift+. 显示隐藏文件。
2. Settings → Pages → Source 选择 **GitHub Actions**（由原来的 Deploy from a branch 改为 Actions）。
3. Actions → **Build reading library** → **Run workflow**，确认 build 和 deploy 均成功。
4. 安装 App 1.7，「连接 GitHub 在线书架」填写 `https://dccchao.github.io/jindianyulu-daily/books.json`。点击「立即联网更新」，再点「阅读来源」选择带「在线」标识的书。

已有 quotes.json 保留，旧版本 App 不受书架格式影响。初始书架为毛选经典短句18条、实践论精选6条；后者是精选短句，不是整篇文章。

## 添加新的原始阅读文件

在 GitHub 的 reading/ 目录点击 Add file → Upload files，上传 TXT、MD、文字版 PDF、DOCX 后提交到 main。不要将完整 Android 工程上传到此仓库。GitHub Actions 会运行转换器，生成 books/book-<编号>.json，更新 books.json，再发布 Pages。App 点击「立即联网更新」后，可在阅读来源中选新书。原文件名（不含扩展名）成为书名，编号由原文件相对路径生成；修改同名文件会更新同一本书，改名会得到新编号。

上传在公开仓库的原文件也是公开文件。Pages 网站只发布网页、书目和语录 JSON，reading、publisher 不放入 Pages artifact。

## 提取规则与限制

转换器与手机使用同一 ReadingText/QuoteRules 源码；DocumentReader 仅从 Android PDFBox 适配到 Java PDFBox 2.0.27，正文处理逻辑相同。过滤目录、标题、注释、脚注、页眉页脚等；PDF 利用字号、书签和重复边缘标签，DOCX 利用样式、样式继承、outline与TOC域。TXT/MD 识别章节和注释结构。保留12–48字的正文片段；超过32字的完整句优先按逗号、分号、冒号等自然停顿组合拆分，保留原标点，不生硬截断、不改写。每条保留书名与摘录编号，不虚构 PDF 页码。

单本书没有可用片段、格式错误或文件超限时，记录 warning 并跳过该书，其他书继续生成和部署；到 Actions 查看文件名和原因。seed 配置或总体资源上限错误仍会使工作流失败。长句多的书可能生成很少条目。无结构文本无法保证100%语义分类，发布前可查看生成结果；经典语录真实性仍应核对来源。

若有误选，可创建 `reviews/<书的id>.json`，内容为 `{"exclude":["需要排除的完整句子。"]}`。id见books.json或生成日志。自动生成会保留此排除清单，不会在更新原文件后重新加入这些句子；全部排除时记录 warning 并跳过该书。对于reading生成的书，不要直接编辑生成的books文件来删除句子，否则下次生成会覆盖手工修改。seed-books.json列出的精选书由手工维护JSON，可直接编辑对应books文件。

每个文件限10 MB、文字内容200万字、PDF最多2000页；扫描PDF先OCR，旧DOC先转DOCX。每本最多20000条/4 MB、书目最多1000本/256 KB。文件从reading/移走后，下次生成的清单不再列出它；手机已下载的缓存仍可离线读。

首次构建需下载Gradle/Kotlin/PDFBox依赖，可能需要数分钟。无需付费服务器或额外 API 密钥，工作流使用 GitHub 内置 GITHUB_TOKEN。若 Save generated library 步骤提示无写权限，到 Settings → Actions → General → Workflow permissions 允许 Read and write permissions。

## 本地生成

有 JDK17 时：
```sh
bash publisher/gradlew -p publisher test jar
java -jar publisher/build/libs/reading-publisher.jar .
```

本地生成也可将 books.json 和 books/ 上传 GitHub，App不依赖自动生成服务。更改工具提取规则后应先通过 tests，再发布。原 Android 项目中的 tools/sync_publisher_rules.py 用于同步共享规则，Robolectric 测试验证真实毛选 PDF 在两个端的结果一致。

官方 Pages 工作流说明：https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages
