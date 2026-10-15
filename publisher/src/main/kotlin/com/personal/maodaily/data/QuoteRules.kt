package com.personal.maodaily.data
/** Shared sentence contract for bundled PDF extracts and user imports. */
object QuoteRules {
 const val MIN_LENGTH=12
 const val MAX_LENGTH=48
 const val MAX_CANDIDATES=20_000
 private val complete=Regex("[。！？!?\\.]([”’\\\"'」』）)])?$")
 private val noise=Regex("(?:https?://|www\\.|[A-Za-z0-9-]+\\.(?:com|cn|org|net)\\b|[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\ufffd])",RegexOption.IGNORE_CASE)
 fun normalize(text:String):String = text.replace(Regex("[⑴-⒇①-⑳⁰¹²³⁴⁵⁶⁷⁸⁹]"),"")
  .replace(Regex("\\s+")," ").replace(Regex("(?<=[\\p{IsHan}，。！？、；：“”‘’（）])\\s+(?=[\\p{IsHan}，。！？、；：“”‘’（）])"),"").trim()
 fun eligible(text:String)=text.length in MIN_LENGTH..MAX_LENGTH && complete.containsMatchIn(text) && !noise.containsMatchIn(text) && text.firstOrNull() !in listOf('，','、','；','：',')','）','”','’')
 fun eligibleFragment(text:String)=eligible(text) || (text.length in MIN_LENGTH..MAX_LENGTH &&
  text.lastOrNull() in listOf('，','；','：',',',';',':') && eligible(text.dropLast(1)+"。"))
 // Select contiguous clauses at natural pauses; never truncate an unbroken clause.
 private fun fragments(text:String):List<String> {
  if(text.length<=32) return listOf(text)
  val clauses=Regex("[^，；：,;:]+[，；：,;:]?").findAll(text).map { it.value.trim() }.toList()
  val best=Array(clauses.size+1) { emptyList<String>() }
  for(i in clauses.indices.reversed()) {
   best[i]=best[i+1]
   var joined=""
   for(j in i until clauses.size) {
    joined+=clauses[j]
    if(joined.length>MAX_LENGTH) break
    if(!eligibleFragment(joined)) continue
    val candidate=listOf(joined)+best[j+1]
    val covered=candidate.sumOf { it.length };val previous=best[i].sumOf { it.length }
    if(covered>previous || (covered==previous && candidate.size<best[i].size)) best[i]=candidate
   }
  }
  return best[0]
 }
 fun select(paragraphs:List<String>):List<String> {
  val sentence=Regex(".+?(?:[。！？!?][”’\\\"'」』）)]?|\\.(?:[”’\\\"']?(?=\\s|$)))")
  return paragraphs.asSequence().map { normalize(it) }.flatMap { sentence.findAll(it).map { match -> match.value.trim() } }
   .flatMap { fragments(it).asSequence() }.filter { eligibleFragment(it) }.distinct().take(MAX_CANDIDATES).toList()
 }
}
