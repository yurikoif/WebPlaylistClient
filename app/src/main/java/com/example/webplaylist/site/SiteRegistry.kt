package com.example.webplaylist.site

class SiteRegistry(
    private val adapters: List<SiteAdapter>,
) {
    fun normalizeUrl(input: String): String {
        val raw = input.trim()
        if (raw.isBlank()) return raw

        adapters.forEach { adapter ->
            val normalized = adapter.normalizeUrl(raw)
            if (adapter.supports(normalized)) return normalized
        }

        return raw
    }

    fun adapterFor(url: String): SiteAdapter {
        return adapters.firstOrNull { it.supports(url) }
            ?: error("Unsupported series URL")
    }

    fun supportedUrlHint(): String {
        return adapters.joinToString { it.displayName }
    }
}
