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
  assertTrue(all.all { QuoteRules.eligible(it) && it.length<=32 })
  assertFalse(all.any { it.contains("陈独秀") || it.contains("一八九一") })
 }
 @Test fun multipleInputsProduceStableIdsAndCatalogAndNoPartialPublishingOnFailure() {
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
   assertFalse(runCatching { Publisher.generate(root) }.isSuccess)
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
