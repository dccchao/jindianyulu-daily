package com.personal.maodaily.data
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
object DocumentReader {
 fun extract(name: String, bytes: ByteArray): String = when(name.substringAfterLast('.',"").lowercase()) {
  "txt","md","markdown" -> ReadingText.decode(bytes)
  "docx" -> docx(bytes)
  "pdf" -> {
   PDDocument.load(bytes).use { document ->
    require(!document.isEncrypted) { "请先移除 PDF 密码后再导入" }
    require(document.numberOfPages<=2000) { "PDF 超过 2000 页，请分成较小的文件" }
    pdfBody(document).also {
     require(it.trim().length>=4) { "这份 PDF 没有可提取的文字，扫描件请先进行文字识别" }
    }
   }
  }
  "doc" -> throw IllegalArgumentException("旧版 DOC 请先另存为 DOCX 后再导入")
  else -> throw IllegalArgumentException("请选择 TXT、MD、PDF 或 DOCX 文件")
 }

 private fun pdfBody(document:PDDocument):String {
  val headings=mutableSetOf<String>()
  val outlinePages=mutableListOf<Pair<Int,String>>()
  fun walk(item:org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem?,depth:Int=0) {
   if(depth>20) return
   var current=item;var count=0
   while(current!=null && count++<2000) {
    val node=current
    val title=node.title ?: ""
    headings.add(title.replace(Regex("\\s+"),""))
    runCatching { node.findDestinationPage(document) }.getOrNull()?.let { page ->
     val index=document.pages.indexOf(page);if(index>=0) outlinePages.add((index+1) to title)
    }
    walk(node.firstChild,depth+1);current=node.nextSibling
   }
  }
  walk(document.documentCatalog.documentOutline?.firstChild)
  val ordered=outlinePages.sortedBy { it.first }
  val excludedPages=mutableSetOf<Int>()
  val nonBody=Regex("^(?:目录|目次|封面|版权页|作者照片|出版说明|本书的出版说明|参考文献|注释|contents|table of contents|copyright|cover|references)$",RegexOption.IGNORE_CASE)
  ordered.forEach { (start,title) ->
   if(nonBody.matches(title.trim())) {
    val end=ordered.firstOrNull { it.first>start }?.first ?: (document.numberOfPages+1)
    excludedPages.addAll(start until end)
   }
  }
  val sizes=mutableMapOf<Int,Int>()
  val measure=object:PDFTextStripper() {
   override fun writeString(text:String,positions:MutableList<TextPosition>) {
    if(currentPageNo in excludedPages) return
    positions.filter { it.unicode.any { ch -> !ch.isWhitespace() } }.forEach { p ->
     val size=(p.fontSizeInPt*2).toInt();sizes[size]=(sizes[size] ?: 0)+p.unicode.length
    }
   }
  }.apply { sortByPosition=true }
  measure.getText(document)
  val dominantCount=sizes.values.maxOrNull() ?: 0
  // Notes can occupy more glyphs than body on annotation-heavy pages.
  // Prefer the largest substantial text cluster, ignoring sparse display titles.
  val bodySize=(sizes.filter { it.value>=dominantCount*0.2f }.keys.maxOrNull() ?: 24)/2f
  val prose=object:PDFTextStripper() {
   override fun writeString(text:String,positions:MutableList<TextPosition>) {
    if(positions.isEmpty()) return
    val label=text.replace(Regex("\\s+"),"")
    if(label in headings || nonBody.matches(text.trim())) return
    val unusual=positions.count { it.fontSizeInPt>bodySize*1.18f || it.fontSizeInPt<bodySize*0.85f }
    if(unusual.toFloat()/positions.size>0.6f) return
    super.writeString(text,positions)
   }
  }.apply { sortByPosition=true;lineSeparator=" ";paragraphEnd="\n\n" }
  val pages=(1..document.numberOfPages).filterNot { it in excludedPages }.map { page -> prose.startPage=page;prose.endPage=page;prose.getText(document) }
  // Repeated short edge labels are running headers/footer text, not prose.
  val edgeCounts=mutableMapOf<String,Int>()
  fun edgeKey(line:String)=line.trim().replace(Regex("\\d+"),"#")
  pages.forEach { page ->
   val lines=page.lines().map { it.trim() }.filter { it.isNotEmpty() }
   (lines.take(1)+lines.takeLast(1)).filter { it.length<80 && !Regex("[。！？!?;；.]").containsMatchIn(it) }
    .map { edgeKey(it) }.distinct().forEach { edgeCounts[it]=(edgeCounts[it] ?: 0)+1 }
  }
  return pages.map { page -> page.lines().filterNot { (edgeCounts[edgeKey(it)] ?: 0)>=2 }.joinToString("\n").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
 }
 private fun docx(bytes:ByteArray):String {
  val parts=mutableMapOf<String,ByteArray>()
  ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
   var entry=zip.nextEntry;var count=0;var total=0
   while(entry!=null && count++<1000) {
    // Bound decompressed bytes as well as compressed input, including skipped media.
    val data=ReadingText.readBounded(zip,20*1024*1024-total);total+=data.size
    if(entry.name in listOf("word/document.xml","word/styles.xml")) parts[entry.name]=data
    zip.closeEntry();entry=zip.nextEntry
   }
  }
  fun parser(bytes:ByteArray)=XmlPullParserFactory.newInstance().newPullParser().apply {
   setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);setInput(ByteArrayInputStream(bytes),null)
  }
  val excluded=mutableSetOf<String>()
  fun nonBody(name:String):Boolean {
   val id=name.lowercase().replace(" ","")
   return Regex("^(?:heading|title|subtitle|toc|footnote|endnote|comment|annotation|caption|header|footer|bibliography|index|标题|副标题|目录|脚注|尾注|批注|题注|页眉|页脚).*",RegexOption.IGNORE_CASE).matches(id)
  }
  val basedOn=mutableMapOf<String,String>()
  parts["word/styles.xml"]?.let { xml ->
   val x=parser(xml);var event=x.eventType;var id=""
   while(event!=XmlPullParser.END_DOCUMENT) {
    if(event==XmlPullParser.START_TAG) {
     val value=x.getAttributeValue("http://schemas.openxmlformats.org/wordprocessingml/2006/main","val") ?: ""
     when(x.name) {
      "style" -> { id=x.getAttributeValue("http://schemas.openxmlformats.org/wordprocessingml/2006/main","styleId") ?: "";if(nonBody(id)) excluded.add(id) }
      "name" -> if(nonBody(value)) excluded.add(id)
      "basedOn" -> basedOn[id]=value
      "outlineLvl" -> if(value.toIntOrNull() in 0..8) excluded.add(id)
     }
    }
    event=x.next()
   }
  }
  repeat(basedOn.size.coerceAtMost(30)) { basedOn.forEach { (id,parent) -> if(nonBody(parent) || parent in excluded) excluded.add(id) } }
  val xml=parts["word/document.xml"] ?: throw IllegalArgumentException("无法提取 Word 正文，请确认文件是 DOCX")
  val x=parser(xml);val out=StringBuilder();val paragraph=StringBuilder()
  var event=x.eventType;var insideText=false;var insideInstruction=false;var skip=false;var tocField=false
  while(event!=XmlPullParser.END_DOCUMENT) {
   if(event==XmlPullParser.START_TAG) {
    val value=x.getAttributeValue("http://schemas.openxmlformats.org/wordprocessingml/2006/main","val") ?: ""
    when(x.name) {
     "p" -> { paragraph.clear();skip=false }
     "pStyle" -> if(nonBody(value) || value in excluded) skip=true
     "outlineLvl" -> if(value.toIntOrNull() in 0..8) skip=true
     "t" -> insideText=true
     "instrText" -> insideInstruction=true
     "fldChar" -> if(x.getAttributeValue("http://schemas.openxmlformats.org/wordprocessingml/2006/main","fldCharType")=="end") tocField=false
    }
   }
   if(event==XmlPullParser.TEXT) {
    if(insideInstruction && Regex("\\bTOC\\b").containsMatchIn(x.text)) { tocField=true;skip=true }
    if(insideText && !tocField) paragraph.append(x.text)
   }
   if(event==XmlPullParser.END_TAG) when(x.name) {
    "t" -> insideText=false
    "instrText" -> insideInstruction=false
    "p" -> if(!skip && !tocField) out.append(paragraph).append("\n\n")
   }
   event=x.next()
  }
  return out.toString()
 }
}
