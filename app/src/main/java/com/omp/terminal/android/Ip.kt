package com.omp.terminal.android

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand

/**
 * `ip addr` and `ip route`, over `NetworkInterface` and `ConnectivityManager`.
 *
 * Both are netlink queries made by the framework, which is the point: `app_neverallows.te` keeps
 * untrusted apps out of `/proc/net` entirely, so `cat /proc/net/route` and `cat /proc/net/if_inet6`
 * both fail with `Permission denied` while these two commands work. That asymmetry is the clearest
 * lesson the device has to offer about how an app is confined.
 */
@CommandSpec(
    name = "ip",
    synopsis = "addr | route",
    group = "android",
    notes = "netlink through NetworkInterface and ConnectivityManager, which is why it works where " +
        "/proc/net is closed to apps; with no active network `ip route` prints nothing",
)
object Ip : FileCommand() {

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        return when (operands.firstOrNull()) {
            "addr" -> addresses(ctx)
            "route" -> route(ctx)
            null -> ctx.fail("ip: no sub-command; available: addr, route")
            else -> ctx.fail("ip: '${operands[0]}' is not supported; available: addr, route")
        }
    }

    private fun addresses(ctx: ExecContext): Int {
        for (ni in ctx.services.networkInterfaces()) {
            ctx.outLine("${ni.name}: <${ni.flags.joinToString(",")}>")
            for (address in ni.addresses) {
                val text = address.substringBefore('/')
                val family = if (text.contains(':')) "inet6" else "inet"
                ctx.outLine("    $family $address scope ${scopeOf(text)}")
            }
        }
        return ExecContext.EXIT_OK
    }

    private fun route(ctx: ExecContext): Int {
        val active = ctx.services.activeRoute() ?: return ExecContext.EXIT_OK
        ctx.outLine("default dev ${active.interfaceName}")
        for (address in active.addresses) {
            ctx.outLine("$address dev ${active.interfaceName}")
        }
        for (server in active.dnsServers) {
            ctx.outLine("dns $server")
        }
        return ExecContext.EXIT_OK
    }
}

/**
 * `ifconfig`, same data in the older layout.
 *
 * The netmask column is missing on purpose: `java.net.InterfaceAddress` reports the IPv4 prefix
 * length as `-1` on Android, and there is no public API that fills it in, so the addresses are
 * printed as the framework has them rather than with a `/24` that would look tidier and be a lie.
 */
@CommandSpec(
    name = "ifconfig",
    synopsis = "[interface ...]",
    group = "android",
    notes = "netlink through NetworkInterface; no netmask column, because Android reports the IPv4 " +
        "prefix length as -1 and no public API supplies it",
)
object Ifconfig : FileCommand() {

    override fun execute(
        ctx: ExecContext,
        flags: String,
        options: Map<String, String>,
        operands: List<String>,
    ): Int {
        val all = ctx.services.networkInterfaces()
        val wanted = if (operands.isEmpty()) all.map { it.name } else operands
        val shown = all.filter { it.name in wanted }
        for (name in wanted) {
            if (shown.none { it.name == name }) return ctx.fail("ifconfig: $name: no such interface")
        }
        ctx.outLine("# netmask is not shown: java.net.InterfaceAddress reports the IPv4 prefix length as -1 on Android")
        for (ni in shown) {
            ctx.outLine("${ni.name}: flags=${iffBits(ni.flags)}<${ni.flags.joinToString(",")}>")
            for (address in ni.addresses) {
                val text = address.substringBefore('/')
                val family = if (text.contains(':')) "inet6" else "inet"
                ctx.outLine("        $family $address")
            }
            if (ni.mac != null) ctx.outLine("        ether ${ni.mac}")
        }
        return ExecContext.EXIT_OK
    }

    /** The `IFF_*` bits the public API can actually determine; `ifconfig` prints the same number. */
    private fun iffBits(flags: List<String>): Int {
        var bits = 0
        for (flag in flags) {
            when (flag) {
                "UP" -> bits = bits or 0x1
                "LOOPBACK" -> bits = bits or 0x8
                "POINTOPOINT" -> bits = bits or 0x10
                "MULTICAST" -> bits or 0x1000
            }
        }
        return bits
    }
}

/** The scope word `ip addr` prints, derived from the address itself: there is no API for it. */
private fun scopeOf(address: String): String {
    if (!address.contains(':')) return if (address.startsWith("127.")) "host" else "global"
    val text = address.lowercase()
    return when {
        text == "::1" -> "host"
        text.startsWith("fe80") -> "link"
        text.startsWith("fec0") -> "site"
        else -> "global"
    }
}
