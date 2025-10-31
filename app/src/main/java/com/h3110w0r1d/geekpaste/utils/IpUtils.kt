package com.h3110w0r1d.geekpaste.utils

import android.util.Log
import java.net.NetworkInterface
import java.net.SocketException

/**
 * IP地址工具类，用于获取本机的所有IP地址
 */
object IpUtils {
    private const val TAG = "IpUtils"

    /**
     * 获取本机的所有IP地址（IPv4和IPv6）
     * @return IP地址列表，格式为字符串（如 "192.168.1.100"）
     */
    fun getAllIpAddresses(): List<String> {
        val ipAddresses = mutableListOf<String>()
        
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            
            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                
                // 跳过回环接口和未启用的接口
                if (networkInterface.isLoopback || !networkInterface.isUp) {
                    continue
                }
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    
                    // 只添加IPv4地址（IPv6地址通常以::开头或包含:）
                    if (!address.isLoopbackAddress && address.hostAddress != null) {
                        val hostAddress = address.hostAddress!!
                        // 过滤掉IPv6地址（包含:）
                        if (!hostAddress.contains(":")) {
                            ipAddresses.add(hostAddress)
                            Log.d(TAG, "发现IP地址: $hostAddress (接口: ${networkInterface.name})")
                        }
                    }
                }
            }
        } catch (e: SocketException) {
            Log.e(TAG, "获取网络接口失败", e)
        } catch (e: Exception) {
            Log.e(TAG, "获取IP地址失败", e)
        }
        
        if (ipAddresses.isEmpty()) {
            Log.w(TAG, "未找到任何IP地址")
        }
        
        return ipAddresses
    }

    /**
     * 获取本机的所有IPv4地址
     * @return IPv4地址列表
     */
    fun getIpv4Addresses(): List<String> {
        return getAllIpAddresses().filter { ip ->
            // 简单的IPv4格式验证（包含点且不包含冒号）
            ip.contains(".") && !ip.contains(":")
        }
    }
}

