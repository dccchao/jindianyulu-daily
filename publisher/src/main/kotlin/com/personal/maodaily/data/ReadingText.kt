package com.personal.maodaily.data
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
object ReadingText {
 const val MAX_BYTES = 10 * 1024 * 1024
 fun readBounded(input: InputStream, limit: Int = MAX_BYTES): ByteArray {
  val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
  while(true) { val n=input.read(buffer);if(n<0) break;require(out.size()+n<=limit) { "文件过大，请选择 10 MB 以内的阅读文件" };out.write(buffer,0,n) }
  return out.toByteArray()
 }
 fun decode(bytes: ByteArray): String {
  require(bytes.isNotEmpty()) { "文件为空" }
  val utf16= bytes.size>=2 && ((bytes[0]==0xff.toByte() && bytes[1]==0xfe.toByte()) || (bytes[0]==0xfe.toByte() && bytes[1]==0xff.toByte()))
  val text = if(utf16) String(bytes,charset("UTF-16")) else {
   require(!bytes.contains(0.toByte())) { "无法读取这个文件，请选择 TXT、MD、PDF 或 DOCX" }
   try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
   catch(e: java.nio.charset.CharacterCodingException) { charset("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
  }
  return text.removePrefix("\uFEFF")
 }

 const val BODY_FILTER_VERSION = 2
 private val trailingAttribution=Regex("""(?<=[。！？!?.”’"'])\s*[—–－-]{1,2}\s*《[^\r\n]{1,160}》\s*$""")
 private val endPunctuation=Regex("[。！？!?；;.]([”’\\\"'）)])?$",RegexOption.IGNORE_CASE)
 private val noteStart=Regex("^(?:\\[\\^?[^]]+]\\s*:?|[〔【][0-9一二三四五六七八九十]+[〕】]|[（(][0-9]+[)）]|(?:注|注释|注解|脚注|译注|编者注|作者注|说明)[：:]|\\d+[.)、]\\s+)")
 private val chapter=Regex("^(?:第[一二三四五六七八九十百零〇0-9]+[章节卷篇部]|(?:chapter|part|section)\\s+[0-9ivxlc]+|[一二三四五六七八九十]+[、．.]|\\d+(?:\\.\\d+)*[.、]\\s+).{0,70}$",RegexOption.IGNORE_CASE)
 private val notesHeading=Regex("^(?:注释|注解|脚注|尾注|参考文献|参考资料|参考书目|附注|译者注|编者注|notes|footnotes|endnotes|references|bibliography)[：:]?$",RegexOption.IGNORE_CASE)
 private fun tocEntry(line:String)=Regex("(?:\\.{2,}|…+|·{2,}|\\t|\\s{2,})\\s*[0-9ivxlc一二三四五六七八九十]+\\s*$",RegexOption.IGNORE_CASE).containsMatchIn(line) || (!endPunctuation.containsMatchIn(line) && Regex("^.{2,100}\\s+[0-9]+$").matches(line))
 fun sentences(text: String, markdown: Boolean = false): List<String> {
  require(text.length<=2_000_000) { "文档内容过长，请分成较小的文件" }
  var content=text.replace("\r\n","\n").replace('\r','\n')
   .replace(Regex("<!--.*?-->",RegexOption.DOT_MATCHES_ALL),"")
  if(markdown) content=content.replace(Regex("(?s)```.*?```|~~~.*?~~~"),"\n")
   .replace(Regex("\\A---\\s*\\n.*?\\n(?:---|\\.\\.\\.)\\s*(?:\\n|$)",RegexOption.DOT_MATCHES_ALL),"")
  val lines=content.lines();val body=mutableListOf<String>()
  var notesSection=false;var noteParagraph=false;var inToc=false
  val pending=StringBuilder()
  fun flush() { if(pending.isNotEmpty()) { body.add(pending.toString());pending.clear() } }
  for((i,raw) in lines.withIndex()) {
   // An attributed excerpt still ends at its sentence punctuation, before the source.
   var line=raw.trim().replace(trailingAttribution,"")
   if(line.isEmpty()) { noteParagraph=false;pending.clear();continue }
   val markdownHeading=markdown && Regex("^#{1,6}\\s+").containsMatchIn(line)
   val heading=line.replace(Regex("^#{1,6}\\s+"),"").trim().removeSuffix("#").trim()
   if(notesHeading.matches(heading)) { notesSection=true;noteParagraph=false;pending.clear();continue }
   if(heading.replace(" ","") in listOf("目录","目次","Contents","TableofContents")) { inToc=true;pending.clear();continue }
   val isChapter=chapter.matches(heading) && !endPunctuation.containsMatchIn(heading)
   if(markdownHeading || isChapter) { if(!notesHeading.matches(heading)) notesSection=false;noteParagraph=false;pending.clear();continue }
   if(notesSection) continue
   if(markdown && (Regex("^[=-]{3,}$").matches(line) || (i+1<lines.size && Regex("^[=-]{3,}$").matches(lines[i+1].trim())))) continue
   if(tocEntry(line)) { pending.clear();continue }
   if(noteStart.containsMatchIn(line)) { noteParagraph=true;pending.clear();continue }
   if(noteParagraph) continue
   if(Regex("^(?:作者|著者|译者|编者|来源|出版|ISBN|版权|日期|时间)[：:].*",RegexOption.IGNORE_CASE).matches(line)) continue
   if(Regex("^(?:第\\s*\\d+\\s*页(?:.*)?|[-—]?\\s*\\d+\\s*[-—]?|\\d{4}[年./-]\\d{1,2}[月./-]\\d{1,2}日?)$").matches(line)) continue
   if(markdown) line=line.replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"),"$1").replace("**","").replace("__","").replace("`","")
   line=line.replace(Regex("[（(【]\\s*(?:注|译注|编者注|作者注)[：:][^）)】]*[）)】]"),"")
    .replace(Regex("\\[\\^?[0-9]+]|[〔【][0-9]+[〕】]"),"").trim()

   line=QuoteRules.normalize(line)
   if(Regex("(?:https?://|www\\.)",RegexOption.IGNORE_CASE).containsMatchIn(line)) { pending.clear();continue }
   if(!endPunctuation.containsMatchIn(line)) {
    // Keep soft-wrapped prose but conservatively discard standalone bare titles.
    if(pending.isNotEmpty() || line.length>=24 || line.contains('，') || line.contains(',')) {
     if(pending.isNotEmpty()) pending.append(' ')
     pending.append(line)
    }
    continue
   }
   if(inToc) inToc=false
   if(pending.isNotEmpty()) pending.append(' ')
   pending.append(line);flush()
  }
  return QuoteRules.select(body)

 }
}
