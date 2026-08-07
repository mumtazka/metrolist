package com.metrolist.desktop.cipher

import java.io.File
import java.security.MessageDigest

object FunctionNameExtractor {
    private const val TAG = "Metrolist_CipherFnExtract"

    data class SigFunctionInfo(
        val name: String,
        val constantArg: Int?,
        val constantArgs: List<Int>? = null,
        val preprocessFunc: String? = null,
        val preprocessArgs: List<Int>? = null,
        val isHardcoded: Boolean = false
    )

    data class NFunctionInfo(
        val name: String,
        val arrayIndex: Int?,
        val constantArgs: List<Int>? = null,
        val isHardcoded: Boolean = false
    )

    data class HardcodedPlayerConfig(
        val sigFuncName: String,
        val sigConstantArg: Int?,
        val sigConstantArgs: List<Int>? = null,
        val sigPreprocessFunc: String? = null,
        val sigPreprocessArgs: List<Int>? = null,
        val nFuncName: String,
        val nArrayIndex: Int?,
        val nConstantArgs: List<Int>? = null,
        val signatureTimestamp: Int
    )

    private val KNOWN_PLAYER_CONFIGS = mapOf(
        "74edf1a3" to HardcodedPlayerConfig(
            sigFuncName = "JI",
            sigConstantArg = 48,
            sigConstantArgs = listOf(48, 1918),
            sigPreprocessFunc = "f1",
            sigPreprocessArgs = listOf(1, 6528),
            nFuncName = "GU",
            nArrayIndex = null,
            nConstantArgs = listOf(6, 6010),
            signatureTimestamp = 20522
        )
    )

    private val Q_ARRAY_PATTERN = Regex("""var\s+Q\s*=\s*"[^"]+"\s*\.\s*split\s*\(\s*"\}"\s*\)""")

    private val PLAYER_HASH_PATTERNS = listOf(
        Regex("""jsUrl['":\s]+[^"']*?/player/([a-f0-9]{8})/"""),
        Regex("""player_ias\.vflset/[^/]+/([a-f0-9]{8})/"""),
        Regex("""/s/player/([a-f0-9]{8})/""")
    )

    private val SIG_FUNCTION_PATTERNS = listOf(
        Regex("""&&\s*\(\s*[a-zA-Z0-9$]+\s*=\s*([a-zA-Z0-9$]+)\s*\(\s*(\d+)\s*,\s*decodeURIComponent\s*\(\s*[a-zA-Z0-9$]+\s*\)"""),
        Regex("""\b[cs]\s*&&\s*[adf]\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("""),
        Regex("""\b[a-zA-Z0-9]+\s*&&\s*[a-zA-Z0-9]+\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("""),
        Regex("""\bm=([a-zA-Z0-9$]{2,})\(decodeURIComponent\(h\.s\)\)"""),
        Regex("""\bc\s*&&\s*d\.set\([^,]+\s*,\s*(?:encodeURIComponent\s*\()([a-zA-Z0-9$]+)\("""),
        Regex("""\bc\s*&&\s*[a-z]\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("""),
    )

    private val N_FUNCTION_PATTERNS = listOf(
        Regex("""\.get\("n"\)\)&&\(b=([a-zA-Z0-9$]+)(?:\[(\d+)\])?\(([a-zA-Z0-9])\)"""),
        Regex("""\.get\("n"\)\)\s*&&\s*\(([a-zA-Z0-9$]+)\s*=\s*([a-zA-Z0-9$]+)(?:\[(\d+)\])?\(\1\)"""),
        Regex("""\(\s*([a-zA-Z0-9$]+)\s*=\s*String\.fromCharCode\(110\)"""),
        Regex("""([a-zA-Z0-9$]+)\s*=\s*function\([a-zA-Z0-9]\)\s*\{[^}]*?enhanced_except_"""),
    )

    fun hasQArrayObfuscation(playerJs: String): Boolean =
        Q_ARRAY_PATTERN.containsMatchIn(playerJs)

    fun extractPlayerHash(playerJs: String): String? {
        for ((index, pattern) in PLAYER_HASH_PATTERNS.withIndex()) {
            val match = pattern.find(playerJs)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        val contentToHash = playerJs.take(10000)
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(contentToHash.toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    fun getHardcodedConfig(playerHash: String): HardcodedPlayerConfig? = KNOWN_PLAYER_CONFIGS[playerHash]

    fun extractSigFunctionInfo(playerJs: String, knownHash: String? = null): SigFunctionInfo? {
        for ((index, pattern) in SIG_FUNCTION_PATTERNS.withIndex()) {
            val match = pattern.find(playerJs)
            if (match != null) {
                val name = match.groupValues[1]
                val constArg = if (match.groupValues.size > 2) match.groupValues[2].toIntOrNull() else null
                return SigFunctionInfo(name, constArg, isHardcoded = false)
            }
        }

        if (hasQArrayObfuscation(playerJs)) {
            val hashToUse = knownHash ?: extractPlayerHash(playerJs)
            if (hashToUse != null) {
                val config = getHardcodedConfig(hashToUse)
                if (config != null) {
                    return SigFunctionInfo(
                        name = config.sigFuncName,
                        constantArg = config.sigConstantArg,
                        constantArgs = config.sigConstantArgs,
                        preprocessFunc = config.sigPreprocessFunc,
                        preprocessArgs = config.sigPreprocessArgs,
                        isHardcoded = true
                    )
                }
            }
        }

        return null
    }

    fun extractNFunctionInfo(playerJs: String, knownHash: String? = null): NFunctionInfo? {
        for ((index, pattern) in N_FUNCTION_PATTERNS.withIndex()) {
            val match = pattern.find(playerJs)
            if (match != null) {
                return when (index) {
                    0 -> {
                        val name = match.groupValues[1]
                        val arrayIdx = match.groupValues[2].toIntOrNull()
                        NFunctionInfo(name, arrayIdx, isHardcoded = false)
                    }
                    1 -> {
                        val name = match.groupValues[2]
                        val arrayIdx = match.groupValues[3].toIntOrNull()
                        NFunctionInfo(name, arrayIdx, isHardcoded = false)
                    }
                    else -> {
                        val name = match.groupValues[1]
                        NFunctionInfo(name, null, isHardcoded = false)
                    }
                }
            }
        }

        if (hasQArrayObfuscation(playerJs)) {
            val hashToUse = knownHash ?: extractPlayerHash(playerJs)
            if (hashToUse != null) {
                val config = getHardcodedConfig(hashToUse)
                if (config != null) {
                    return NFunctionInfo(
                        config.nFuncName,
                        config.nArrayIndex,
                        config.nConstantArgs,
                        isHardcoded = true
                    )
                }
            }
        }

        return null
    }

    fun extractSignatureTimestamp(playerJs: String): Int? {
        val patterns = listOf(
            Regex("""signatureTimestamp['":\s]+(\d+)"""),
            Regex("""sts['":\s]+(\d+)"""),
            Regex(""""signatureTimestamp"\s*:\s*(\d+)""")
        )
        for ((index, pattern) in patterns.withIndex()) {
            val match = pattern.find(playerJs)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        val playerHash = extractPlayerHash(playerJs)
        return getHardcodedConfig(playerHash ?: return null)?.signatureTimestamp
    }

    fun analyzePlayerJs(playerJs: String, knownHash: String? = null): PlayerAnalysis {
        val playerHash = knownHash ?: extractPlayerHash(playerJs)
        val hasQArray = hasQArrayObfuscation(playerJs)
        val sigInfo = extractSigFunctionInfo(playerJs, playerHash)
        val nFuncInfo = extractNFunctionInfo(playerJs, playerHash)
        val signatureTimestamp = extractSignatureTimestamp(playerJs)

        return PlayerAnalysis(
            playerHash = playerHash,
            hasQArrayObfuscation = hasQArray,
            sigInfo = sigInfo,
            nFuncInfo = nFuncInfo,
            signatureTimestamp = signatureTimestamp
        )
    }

    data class PlayerAnalysis(
        val playerHash: String?,
        val hasQArrayObfuscation: Boolean,
        val sigInfo: SigFunctionInfo?,
        val nFuncInfo: NFunctionInfo?,
        val signatureTimestamp: Int?
    )
}
