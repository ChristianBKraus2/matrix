package com.shadowrun.matrix.network

class Matrix(val rtgs: List<RTG> = emptyList()) {
    fun getRTG(name: String): RTG? = rtgs.firstOrNull { it.name == name }
    fun getLTG(rtgName: String, ltgName: String): LTG? = getRTG(rtgName)?.ltgs?.firstOrNull { it.name == ltgName }
    fun getHost(rtgName: String, ltgName: String, hostName: String): Host? {
        val ltg = getLTG(rtgName, ltgName) ?: return null
        return ltg.hosts.firstOrNull { it.name == hostName }
            ?: ltg.pltgs.flatMap { it.hosts }.firstOrNull { it.name == hostName }
    }
}
