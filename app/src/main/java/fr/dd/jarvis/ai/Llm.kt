 * fournisseur sont en pause, on passe immédiatement au suivant.
 */
class Llm(private val store: Store) {
    val groqRing = KeyRing({ store.groqKeys.toList() }, quotaPartagee = true)
    private val geminiRing = KeyRing { store.geminiKeys.toList().filterNot(KeyRing::estCleOpenAi) }
    val openaiRing = KeyRing { store.openaiKeys.toList() }
    val groq = GroqClient(groqRing) { store.groqModel.value }
    val openai = OpenAiClient(openaiRing) { store.openaiModel.value }

    /** (clés, en pause, refus de quota dans la dernière heure) — tableau de santé. */