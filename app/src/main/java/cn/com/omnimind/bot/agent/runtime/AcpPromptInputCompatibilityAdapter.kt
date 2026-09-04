package cn.com.omnimind.bot.agent.runtime

/**
 * Converts official ACP prompt content blocks into the local executor's
 * existing text/attachments input. This is an input-boundary adapter only;
 * it does not create a second prompt protocol or alter durable history.
 */
internal object AcpPromptInputCompatibilityAdapter {
    fun normalize(args: Map<String, Any?>): Map<String, Any?> {
        val rawInput = args["input"] ?: return args
        val input = rawInput as? List<*>
            ?: throw IllegalArgumentException("ACP prompt input must be a content-block list")
        if (input.isEmpty()) {
            throw IllegalArgumentException("ACP prompt input is empty")
        }
        val textParts = mutableListOf<String>()
        val attachments = mutableListOf<Map<String, Any?>>()

        input.forEach { rawBlock ->
            val block = rawBlock as? Map<*, *>
                ?: throw IllegalArgumentException("ACP prompt contains a malformed content block")
            val type = block["type"]?.toString()?.trim()?.lowercase().orEmpty()
            when (type) {
                "text" -> block["text"]?.toString()
                    ?.takeIf(String::isNotBlank)
                    ?.let(textParts::add)
                    ?: throw IllegalArgumentException("ACP text content block is empty")

                "image", "audio" -> {
                    val mimeType = block["mimeType"]?.toString()?.trim()
                        .orEmpty()
                        .ifEmpty {
                            if (type == "image") "image/*" else "audio/*"
                        }
                    val data = block["data"]?.toString()?.trim().orEmpty()
                    val uri = block["uri"]?.toString()?.trim().orEmpty()
                    if (data.isEmpty() && uri.isEmpty()) {
                        throw IllegalArgumentException("ACP $type content block has no data or uri")
                    }
                    attachments += buildMap {
                        put("name", if (type == "image") "image" else "audio")
                        put("fileName", if (type == "image") "image" else "audio")
                        put("mimeType", mimeType)
                        put("isImage", type == "image")
                        put("isAudio", type == "audio")
                        put("sendToModel", false)
                        if (data.isNotEmpty()) {
                            put(
                                "dataUrl",
                                if (data.startsWith("data:", ignoreCase = true)) {
                                    data
                                } else {
                                    "data:$mimeType;base64,$data"
                                }
                            )
                        }
                        if (uri.isNotEmpty()) put("path", uri)
                    }
                }

                "resource_link" -> {
                    val uri = block["uri"]?.toString()?.trim().orEmpty()
                    if (uri.isEmpty()) {
                        throw IllegalArgumentException("ACP resource_link has no uri")
                    }
                    val mimeType = block["mimeType"]?.toString()?.trim()
                        .orEmpty()
                        .ifEmpty { "application/octet-stream" }
                    val isImage = mimeType.startsWith("image/", ignoreCase = true)
                    attachments += buildMap {
                        put("name", block["name"]?.toString().orEmpty().ifEmpty { "attachment" })
                        put("fileName", block["name"]?.toString().orEmpty().ifEmpty { "attachment" })
                        put("mimeType", mimeType)
                        put("isImage", isImage)
                        put("sendToModel", false)
                        put("path", uri)
                        block["size"]?.let { put("size", it) }
                    }
                }

                "resource" -> {
                    val resource = block["resource"] as? Map<*, *>
                        ?: throw IllegalArgumentException("ACP resource block is malformed")
                    val mimeType = resource["mimeType"]?.toString()?.trim()
                        .orEmpty()
                        .ifEmpty { "application/octet-stream" }
                    val uri = resource["uri"]?.toString()?.trim().orEmpty()
                    val text = resource["text"]?.toString()
                    if (!text.isNullOrBlank()) {
                        textParts += text
                    } else {
                        val blob = resource["blob"]?.toString()?.trim().orEmpty()
                        if (blob.isNotEmpty()) {
                            attachments += buildMap {
                                put("name", uri.ifEmpty { "resource" })
                                put("fileName", uri.ifEmpty { "resource" })
                                put("mimeType", mimeType)
                                put("isImage", mimeType.startsWith("image/", ignoreCase = true))
                                put("sendToModel", false)
                                put("dataUrl", "data:$mimeType;base64,$blob")
                                if (uri.isNotEmpty()) put("path", uri)
                            }
                        } else if (uri.isNotEmpty()) {
                            attachments += buildMap {
                                put("name", uri)
                                put("fileName", uri)
                                put("mimeType", mimeType)
                                put("isImage", mimeType.startsWith("image/", ignoreCase = true))
                                put("sendToModel", false)
                                put("path", uri)
                            }
                        } else {
                            throw IllegalArgumentException("ACP resource has no text, blob, or uri")
                        }
                    }
                }

                else -> throw IllegalArgumentException(
                    "Unsupported ACP prompt content block type: ${type.ifEmpty { "<missing>" }}"
                )
            }
        }

        if (textParts.isEmpty() && attachments.isEmpty()) {
            throw IllegalArgumentException("ACP prompt contains no usable content")
        }
        return LinkedHashMap(args).apply {
            if (textParts.isNotEmpty()) put("text", textParts.joinToString("\n"))
            if (attachments.isNotEmpty()) put("attachments", attachments)
        }
    }
}
