package com.personal.maodaily.publisher
import com.personal.maodaily.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream

class PublisherTest {
 @Test fun longSentencesSplitOnlyAtNaturalPauses() {
  val clauses=listOf("读书让我们看到生活中更多的可能，","认真思考让我们理解这些不同的选择；","持续实践才能找到适合自己的方向。")
  val source=clauses.joinToString("")
  val parts=ReadingText.sentences(source)
  assertTrue(parts.size>=2)
  assertEquals(source,parts.joinToString(""))
  assertTrue(parts.all { it.length in 12..48 && QuoteRules.eligibleFragment(it) })
  assertTrue(ReadingText.sentences("没有自然停顿的正文".repeat(10)+"。").isEmpty())
  val short="读书让我们看到生活的更多可能。"
  assertEquals(listOf(short),ReadingText.sentences(short))
 }
 @Test fun failedBooksWarnWhileSeedsReviewsAndNewBooksContinue() {
  val root=Files.createTempDirectory("reading-failure-test").toFile()
  val previous=System.err;val warnings=ByteArrayOutputStream()
  try {
   System.setErr(java.io.PrintStream(warnings,true,"UTF-8"))
   root.resolve("reading").mkdirs();root.resolve("books").mkdirs();root.resolve("reviews").mkdirs()
   val short="读书让我们看到生活的更多可能。"
   root.resolve("books/seed.json").writeText(BookCompiler.compile("seed.txt",short.toByteArray(),"精选").toString())
   root.resolve("seed-books.json").writeText("""{"version":1,"books":[{"id":"seed","title":"精选","path":"books/seed.json"}]}""")
   root.resolve("reading/坏书.txt").writeText("只有目录，没有正文")
   root.resolve("reading/损坏.docx").writeBytes(byteArrayOf(1,2,3))
   root.resolve("reading/全部排除.txt").writeText(short)
   val blockedId=BookCompiler.stableId("全部排除.txt")
   root.resolve("reviews/$blockedId.json").writeText("""{"exclude":["$short"]}""")
   root.resolve("reading/好书.txt").writeText(short)
   Publisher.generate(root)
   val books=JSONObject(root.resolve("books.json").readText()).getJSONArray("books")
   assertEquals(2,books.length())
   assertEquals("seed",books.getJSONObject(0).getString("id"))
   val goodId=BookCompiler.stableId("好书.txt")
   assertEquals(goodId,books.getJSONObject(1).getString("id"))
   assertEquals(1,BookCompiler.validateCorpus(root.resolve("books/$goodId.json").readText()))
   assertFalse(root.resolve("books/$blockedId.json").exists())
   val log=warnings.toString("UTF-8")
   assertTrue(log.contains("::warning::跳过书籍 坏书.txt"))
   assertTrue(log.contains("损坏.docx"));assertTrue(log.contains("人工排除后没有可用语录"))
  } finally { System.setErr(previous);root.deleteRecursively() }
 }
 @Test fun markdownExcludesTitlesContentsAndFootnotesWithoutCuttingLongSentences() {
  val text="""---
title: 元数据
---
# 文章标题
目录
第一章 ........ 12
# 第一章
读书让我们看到生活的更多可能。
<!-- 注释中的文字不能被选择进入语录。 -->
[^1]: 注释中的文字不能被选择进入语录。

${"很长的正文".repeat(9)}。

每天读一点，日子便有了新的方向。
## 注释
这段注释中的完整句子不能入库。
"""
  val book=BookCompiler.compile("book.md",text.toByteArray(),"测试书")
  val quotes=book.getJSONArray("quotes")
  assertEquals(2,quotes.length())
  assertEquals("读书让我们看到生活的更多可能。",quotes.getJSONObject(0).getString("text"))
  assertTrue(quotes.getJSONObject(1).getString("source").contains("测试书"))
 }
 @Test fun docxUsesExactStylesFiltering() {
  val bytes=ByteArrayOutputStream()
  ZipOutputStream(bytes).use { out ->
   out.putNextEntry(ZipEntry("word/document.xml"))
   out.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>这个文章标题不应当出现在语录中。</w:t></w:r></w:p><w:p><w:r><w:t>读书让我们看到生活的更多可能。</w:t></w:r></w:p></w:body></w:document>""".toByteArray());out.closeEntry()
  }
  val quotes=BookCompiler.compile("book.docx",bytes.toByteArray(),"测试书").getJSONArray("quotes")
  assertEquals(1,quotes.length())
 }
 @Test fun actualMaoPdfOmitsNotesAndUsesSharedRuleImplementation() {
  val bytes=javaClass.getResourceAsStream("/maoxuan-layout-sample.pdf")!!.use { it.readBytes() }
  val book=BookCompiler.compile("book.pdf",bytes,"毛选样本")
  val quotes=book.getJSONArray("quotes")
  val all=(0 until quotes.length()).map { quotes.getJSONObject(it).getString("text") }
  assertTrue(all.isNotEmpty())
  assertTrue(all.all { QuoteRules.eligibleFragment(it) && it.length<=48 })
  assertFalse(all.any { it.contains("陈独秀") || it.contains("一八九一") })
 }
 @Test fun multipleInputsProduceStableIdsAndSkipFailedBooks() {
  val root=Files.createTempDirectory("reading-publisher-test").toFile()
  try {
   val reading=root.resolve("reading").apply { mkdirs() }
   reading.resolve("甲书.txt").writeText("读书让我们看到生活的更多可能。")
   reading.resolve("乙书.md").writeText("# 标题\n\n每天读一点，日子便有了新的方向。")
   Publisher.generate(root)
   val catalog=root.resolve("books.json").readText()
   assertEquals(2,JSONObject(catalog).getJSONArray("books").length())
   Publisher.generate(root)
   assertEquals(catalog,root.resolve("books.json").readText())
   reading.resolve("错误.txt").writeText("只有目录，没有正文")
   assertTrue(runCatching { Publisher.generate(root) }.isSuccess)
   assertEquals(catalog,root.resolve("books.json").readText())
  } finally { root.deleteRecursively() }
 }
 @Test fun excludesEmptyBodyOldDocAndOversizedFile() {
  assertFalse(runCatching { BookCompiler.compile("a.txt","目录\n第一章 .... 12".toByteArray(),"书") }.isSuccess)
  assertFalse(runCatching { BookCompiler.compile("a.doc",byteArrayOf(1),"书") }.isSuccess)
  assertFalse(runCatching { BookCompiler.compile("a.txt",ByteArray(ReadingText.MAX_BYTES+1),"书") }.isSuccess)
 }
 @Test fun manualExclusionsPersistAcrossRegeneration() {
  val root=Files.createTempDirectory("reading-review-test").toFile()
  try {
   root.resolve("reading").mkdirs()
   root.resolve("reading/书.txt").writeText("读书让我们看到生活的更多可能。\n\n每天读一点，日子便有了新的方向。")
   val id=BookCompiler.stableId("书.txt")
   root.resolve("reviews").mkdirs()
   root.resolve("reviews/$id.json").writeText("""{"exclude":["读书让我们看到生活的更多可能。"]}""")
   repeat(2) { Publisher.generate(root) }
   val quotes=JSONObject(root.resolve("books/$id.json").readText()).getJSONArray("quotes")
   assertEquals(1,quotes.length())
   assertEquals("每天读一点，日子便有了新的方向。",quotes.getJSONObject(0).getString("text"))
  } finally { root.deleteRecursively() }
 }
}
