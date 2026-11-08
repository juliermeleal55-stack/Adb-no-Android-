package com.adbnoandroid

/**
 * Procura a ÚLTIMA ocorrência do marcador de exit code na saída do comando.
 * (A última é sempre a do nosso `echo`, executado por último — o comando do
 * usuário pode até imprimir o token por coincidência, que não importa.)
 *
 * @return (saída limpa, código de saída) ou null quando o marcador não existe
 *   (ou seja, o comando nunca chegou a rodar — ex.: su negado).
 */
fun findExitMarker(raw: String): Pair<String, Int?>? {
    val regex = Regex("${Regex.escape(Adb.EXIT_MARKER)}:(-?\\d+)")
    val last = regex.findAll(raw).lastOrNull() ?: return null
    val clean = raw.substring(0, last.range.first).trimEnd()
    val code = last.groupValues[1].toIntOrNull()
    return clean to code
}
