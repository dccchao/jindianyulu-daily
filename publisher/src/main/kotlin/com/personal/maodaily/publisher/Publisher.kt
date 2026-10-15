package com.personal.maodaily.publisher

import com.personal.maodaily.data.DocumentReader
import com.personal.maodaily.data.QuoteRules
import com.personal.maodaily.data.ReadingText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

object BookCompiler {
 fun compile(name:String,bytes:ByteArray,title:String):JSONObject {
  require(bytes.size<=ReadingText.MAX_BYTES) { "$name 超过10 MB，请分成较小文件" }
  require(title.length in 1..80)
  val text=DocumentReader.extract(name,bytes)
  val body=ReadingText.sentences(text,name.substringAfterLast('.').lowercase() in listOf("md","markdown"))
  val short=body
  require(short.isNotEmpty()) { "$name 排除目录、标题、注释和超长句后，没有可用的12–48字正文片段；请整理正文或改用其他书" }
  val quotes=JSONArray(short.mapIndexed { index,line -> JSONObject().put("id",index+1).put("text",line).put("source","《$title》 · 正文摘录 ${index+1}") })
  return JSONObject().put("version",1).put("title",title).put("quotes",quotes)
   .put("bodyFilterVersion",ReadingText.BODY_FILTER_VERSION).put("originalBodySentences",body.size)
   .put("omittedLongSentences",body.size-short.size).put("sourceSha256",hash(bytes)).also {
    require(it.toString().toByteArray().size<=4*1024*1024) { "$name 生成的语录库超过4 MB" }
   }
 }
 private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
 fun stableId(relative:String)="book-"+hash(relative.toByteArray()).take(16)
 fun validateCorpus(raw:String):Int {
  val doc=JSONObject(raw);require(doc.getInt("version")==1)
  val quotes=doc.getJSONArray("quotes");require(quotes.length() in 1..20_000)
  val ids=mutableSetOf<Int>();val texts=mutableSetOf<String>()
  for(index in 0 until quotes.length()) {
   val quote=quotes.getJSONObject(index);val text=quote.getString("text")
   require(quote.getInt("id")>0 && ids.add(quote.getInt("id")) && texts.add(text))
   require(QuoteRules.eligibleFragment(text) && quote.getString("source").length in 1..160)
  }
  require(raw.toByteArray().size<=4*1024*1024)
  return quotes.length()
 }
}
object Publisher {
 fun generate(root:File) {
  require(root.isDirectory)
  val reading=File(root,"reading").apply { mkdirs() }
  val outputs=linkedMapOf<String,String>();val entries=mutableListOf<JSONObject>();val ids=mutableSetOf<String>()
  val seed=File(root,"seed-books.json")
  if(seed.exists()) {
   val seedDoc=JSONObject(seed.readText());require(seedDoc.getInt("version")==1)
   val books=seedDoc.getJSONArray("books")
   for(index in 0 until books.length()) {
    val book=books.getJSONObject(index);val id=book.getString("id");val path=book.getString("path")
    require(Regex("[a-z0-9][a-z0-9_-]{0,63}").matches(id) && ids.add(id))
    require(Regex("books/[A-Za-z0-9_-]+\\.json").matches(path))
    require(book.getString("title").length in 1..80)
    book.put("count",BookCompiler.validateCorpus(File(root,path).readText()));entries.add(book)
   }
  }
  val files=reading.walkTopDown().filter { it.isFile && !it.name.startsWith('.') }
   .sortedBy { it.relativeTo(reading).invariantSeparatorsPath }.toList()
  require(files.size+entries.size<=1000) { "最多1000本书" }

  for(file in files) {
   try {
    require(!Files.isSymbolicLink(file.toPath())) { "不接受符号链接" }
    require(file.length()<=ReadingText.MAX_BYTES) { "${file.name} 超过10 MB" }
    val id=BookCompiler.stableId(file.relativeTo(reading).invariantSeparatorsPath);require(ids.add(id))
    val title=file.nameWithoutExtension.take(80)
    val document=BookCompiler.compile(file.name,file.readBytes(),title)
    val review=File(root,"reviews/$id.json")
    if(review.exists()) {
     require(review.length()<=4*1024*1024)
     val excluded=JSONObject(review.readText()).getJSONArray("exclude");require(excluded.length()<=20_000)
     val blocked=(0 until excluded.length()).map { QuoteRules.normalize(excluded.getString(it)) }.toSet()
     val quotes=document.getJSONArray("quotes")
     val retained=(0 until quotes.length()).map { quotes.getJSONObject(it) }.filterNot { it.getString("text") in blocked }
     require(retained.isNotEmpty()) { "${file.name} 人工排除后没有可用语录" }
     document.put("quotes",JSONArray(retained)).put("manualExclusions",quotes.length()-retained.size)
    }
    val path="books/$id.json";outputs[path]=document.toString(2)+"\n"
    entries.add(JSONObject().put("id",id).put("title",title).put("path",path).put("count",document.getJSONArray("quotes").length()))
    println("${file.name} [$id]: ${document.getJSONArray("quotes").length()} 条正文片段")
   } catch(e:Exception) {
    val warning="${file.relativeTo(reading).invariantSeparatorsPath}: ${e.message ?: e.javaClass.simpleName}"
     .replace("%","%25").replace("\r","%0D").replace("\n","%0A")
    System.err.println("::warning::跳过书籍 $warning")
   }
  }
  val generatedBytes=outputs.values.sumOf { it.toByteArray().size }
  require(generatedBytes<=64*1024*1024) { "本次生成超过64 MB，请分成不同书架" }
  require(entries.size<=1000) { "最多1000本书" }
  val catalog=JSONObject().put("version",1).put("books",JSONArray(entries)).toString(2)+"\n"
  require(catalog.toByteArray().size<=256*1024)
  // Validate all inputs before publishing any output; the catalog is replaced last.
  outputs.forEach { (path,raw) -> atomicWrite(File(root,path),raw) }
  atomicWrite(File(root,"books.json"),catalog)
  println("书架生成完成：${entries.size} 本书")
 }
 private fun atomicWrite(file:File,raw:String) {
  file.parentFile.mkdirs();val temporary=Files.createTempFile(file.parentFile.toPath(),".publish-",".tmp")
  try { Files.writeString(temporary,raw);Files.move(temporary,file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
  finally { Files.deleteIfExists(temporary) }
 }
}
fun main(args:Array<String>) {
 require(args.size==1) { "用法：java -jar reading-publisher.jar <站点目录>" }
 Publisher.generate(File(args[0]).canonicalFile)
}
