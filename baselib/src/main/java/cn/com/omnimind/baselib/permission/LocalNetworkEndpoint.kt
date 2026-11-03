package cn.com.omnimind.baselib.permission

import java.net.InetAddress
import java.net.URI

/** Classifies a destination before Android's local-network permission prompt. */
internal fun isLocalNetworkEndpoint(
    endpoint: String,
    resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
): Boolean {
    val host = runCatching { URI(endpoint.trim()).host }
        .getOrNull()?.removeSurrounding("[", "]")?.trimEnd('.')?.lowercase()
        ?.takeIf { it.isNotEmpty() } ?: return false
    if (host == "localhost" || host.endsWith(".localhost")) return false
    // mDNS resolution itself requires permission, so inspect the suffix first.
    if (host.endsWith(".local")) return true
    return runCatching { resolve(host).any(::isLocalNetworkAddress) }.getOrDefault(false)
}

internal fun isLocalNetworkAddress(address: InetAddress): Boolean {
    if (address.isLoopbackAddress) return false
    if (address.isSiteLocalAddress || address.isLinkLocalAddress ||
        address.isAnyLocalAddress || address.isMulticastAddress
    ) return true
    val bytes = address.address
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    return when (bytes.size) {
        16 -> first and 0xfe == 0xfc // IPv6 unique-local addresses.
        4 -> (first == 100 && second in 64..127) || bytes.all { it == (-1).toByte() }
        else -> false
    }
}
