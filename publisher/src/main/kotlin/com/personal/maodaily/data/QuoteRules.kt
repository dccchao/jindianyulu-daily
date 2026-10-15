package com.personal.maodaily.data
/** Shared sentence contract for bundled PDF extracts and user imports. */
object QuoteRules {
 const val MIN_LENGTH=12
 const val MAX_LENGTH=100
 const val MAX_CANDIDATES=20_000
 private val complete=Regex("[。！？!?\\.]([”’\\\"'」』）)])?$")
 private val noise=Regex("(?:https?://|www\\.|[A-Za-z0-9-]+\\.(?:com|cn|org|net)\\b|[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\ufffd])",RegexOption.IGNORE_CASE)
 fun normalize(text:String):String = text.replace(Regex("[⑴-⒇①-⑳⁰¹²³⁴⁵⁶⁷⁸⁹]"),"")
  .replace(Regex("\\s+")," ").replace(Regex("(?<=[\\p{IsHan}，。！？、；：“”‘’（）])\\s+(?=[\\p{IsHan}，。！？、；：“”‘’（）])"),"").trim()
 fun eligible(text:String)=text.length in MIN_LENGTH..MAX_LENGTH && complete.containsMatchIn(text) && !noise.containsMatchIn(text) && text.firstOrNull() !in listOf('，','、','；','：',')','）','”','’')
 fun select(paragraphs:List<String>):List<String> {
  val sentence=Regex(".+?(?:[。！？!?][”’\\\"'」』）)]?|\\.(?:[”’\\\"']?(?=\\s|$)))")
  return paragraphs.asSequence().map { normalize(it) }.flatMap { sentence.findAll(it).map { match -> match.value.trim() } }
   .filter { eligible(it) }.distinct().take(MAX_CANDIDATES).toList()
 }
}
