package com.h3110w0r1d.geekpaste.utils

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.min

/**
 * BLE 数据分片处理器
 * 负责处理数据的分片发送和接收合并
 */
class BleDataFragmentHandler {
    companion object {
        private const val FRAGMENT_SEND_DELAY_MS = 50L // 分片发送间隔（毫秒）
        private const val FRAGMENT_HEADER_SIZE = 2 // 分片头大小（序号 + 总数）
        private const val MAX_FRAGMENT_COUNT = 256 // 最大分片数量
    }

    // 分片接收缓冲区：设备地址 -> 分片索引 -> 分片数据
    private val fragmentBuffersByDevice = mutableMapOf<String, MutableMap<Int, ByteArray>>()

    // 预期的分片总数：设备地址 -> 分片总数
    private val expectedFragmentCountsByDevice = mutableMapOf<String, Int>()

    /**
     * 使用 Gzip 压缩数据
     * @param data 原始数据
     * @return 压缩后的数据
     */
    private fun compressData(data: ByteArray): ByteArray {
        val outputStream = ByteArrayOutputStream()
        GZIPOutputStream(outputStream).use { gzipOut ->
            gzipOut.write(data)
            gzipOut.finish()
        }
        return outputStream.toByteArray()
    }

    /**
     * 使用 Gzip 解压数据
     * @param compressedData 压缩后的数据
     * @return 解压后的原始数据
     */
    private fun decompressData(compressedData: ByteArray): ByteArray {
        val inputStream = compressedData.inputStream()
        GZIPInputStream(inputStream).use { gzipIn ->
            return gzipIn.readBytes()
        }
    }

    /**
     * 计算分片信息
     * @param dataSize 数据总大小
     * @param mtu 最大传输单元大小
     * @return Pair<分片总数, 每个分片的载荷大小>
     */
    fun calculateFragmentInfo(
        dataSize: Int,
        mtu: Int,
    ): Pair<Int, Int> {
        val fragmentPayloadSize = mtu - FRAGMENT_HEADER_SIZE
        val totalFragments = (dataSize + fragmentPayloadSize - 1) / fragmentPayloadSize
        require(totalFragments <= MAX_FRAGMENT_COUNT) {
            "数据太大，无法发送: $dataSize 字节，需要 $totalFragments 个分片"
        }
        return Pair(totalFragments, fragmentPayloadSize)
    }

    /**
     * 发送分片数据到设备
     * @param gatt GATT 连接
     * @param characteristic 特征
     * @param data 要发送的完整数据（不会被修改）
     * @param mtu MTU 大小
     * @param writeFunction 写入特征的函数
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun sendFragmentedData(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        mtu: Int,
        writeFunction: suspend (BluetoothGatt, BluetoothGattCharacteristic, ByteArray) -> Unit,
    ) {
        // 在发送分片之前进行 Gzip 压缩
        val compressedData = compressData(data)
        val (totalFragments, fragmentPayloadSize) = calculateFragmentInfo(compressedData.size, mtu)

        // 预分配分片数组，避免每次循环都创建新数组
        val maxFragmentSize = FRAGMENT_HEADER_SIZE + fragmentPayloadSize
        val fragmentBuffer = ByteArray(maxFragmentSize)

        for (fragmentIndex in 0 until totalFragments) {
            if (fragmentIndex > 0) {
                delay(FRAGMENT_SEND_DELAY_MS)
            }

            val startPos = fragmentIndex * fragmentPayloadSize
            val endPos = min(startPos + fragmentPayloadSize, compressedData.size)
            val payloadSize = endPos - startPos

            // 直接填充分片缓冲区，避免创建中间数组
            fragmentBuffer[0] = fragmentIndex.toByte()
            fragmentBuffer[1] = totalFragments.toByte()
            System.arraycopy(compressedData, startPos, fragmentBuffer, FRAGMENT_HEADER_SIZE, payloadSize)

            // 大多数情况下分片大小等于缓冲区大小，可以直接使用
            // 只有最后一个分片可能需要创建精确大小的数组
            val fragment =
                if (payloadSize == fragmentPayloadSize) {
                    fragmentBuffer
                } else {
                    // 最后一个分片，创建精确大小的数组
                    val actualFragmentSize = FRAGMENT_HEADER_SIZE + payloadSize
                    ByteArray(actualFragmentSize).apply {
                        System.arraycopy(fragmentBuffer, 0, this, 0, actualFragmentSize)
                    }
                }

            writeFunction(gatt, characteristic, fragment)
        }
    }

    /**
     * 处理接收到的分片数据
     * @param deviceAddress 设备地址
     * @param value 接收到的分片数据（包含头部）
     * @return 如果接收完整，返回合并后的完整数据；否则返回 null
     */
    fun processReceivedFragment(
        deviceAddress: String,
        value: ByteArray,
    ): ByteArray? {
        if (value.size < FRAGMENT_HEADER_SIZE) return null

        // 提取分片信息（避免重复计算）
        val fragmentIndex = value[0].toInt() and 0xFF
        val totalFragments = value[1].toInt() and 0xFF
        val fragmentDataSize = value.size - FRAGMENT_HEADER_SIZE

        // 处理第一个分片：重新初始化缓冲区
        if (fragmentIndex == 0) {
            if (totalFragments == 1) {
                // 只有单个分片，直接返回，避免创建缓冲区和数组拷贝
                // 注意：这里需要创建新数组，因为原始数组可能被重用
                val mergedData =
                    ByteArray(fragmentDataSize).apply {
                        System.arraycopy(value, FRAGMENT_HEADER_SIZE, this, 0, fragmentDataSize)
                    }
                // 合并分片之后进行 Gzip 解压
                return decompressData(mergedData)
            }
            // 复用或创建缓冲区
            val buffer = fragmentBuffersByDevice.getOrPut(deviceAddress) { mutableMapOf() }
            buffer.clear() // 清空旧数据，避免内存泄漏
            // 创建分片数据副本，避免原始数组被重用导致的问题
            buffer[0] =
                ByteArray(fragmentDataSize).apply {
                    System.arraycopy(value, FRAGMENT_HEADER_SIZE, this, 0, fragmentDataSize)
                }
            expectedFragmentCountsByDevice[deviceAddress] = totalFragments
            return null
        }

        val buffer = fragmentBuffersByDevice[deviceAddress] ?: return null
        val expectedCount = expectedFragmentCountsByDevice[deviceAddress]

        // 检查总分片数是否一致
        if (expectedCount != null && totalFragments != expectedCount) {
            return null
        }

        // 保存分片（创建副本，避免原始数组被重用）
        buffer[fragmentIndex] =
            ByteArray(fragmentDataSize).apply {
                System.arraycopy(value, FRAGMENT_HEADER_SIZE, this, 0, fragmentDataSize)
            }

        // 检查是否接收到所有分片
        if (buffer.size == totalFragments) {
            val mergedData = mergeFragments(buffer)
            clearDeviceFragments(deviceAddress)
            // 合并分片之后进行 Gzip 解压
            return decompressData(mergedData)
        }

        return null
    }

    /**
     * 合并分片数据（性能优化版本）
     * 使用预分配大小和直接数组拷贝，避免多次分配
     * 优化：使用已知的分片数量，避免排序操作
     */
    private fun mergeFragments(fragments: MutableMap<Int, ByteArray>): ByteArray {
        if (fragments.isEmpty()) return ByteArray(0)

        // 计算总大小（单次遍历）
        var totalSize = 0
        var maxIndex = -1
        for ((index, fragment) in fragments) {
            totalSize += fragment.size
            if (index > maxIndex) maxIndex = index
        }

        // 如果分片索引连续（从0开始），可以直接按索引顺序合并，无需排序
        val mergedData = ByteArray(totalSize)
        var offset = 0

        // 如果分片数量等于最大索引+1，说明分片连续，可以直接按索引顺序合并
        if (fragments.size == maxIndex + 1) {
            for (i in 0..maxIndex) {
                val fragment = fragments[i]!!
                System.arraycopy(fragment, 0, mergedData, offset, fragment.size)
                offset += fragment.size
            }
        } else {
            // 分片不连续，需要排序（这种情况较少见）
            val sortedKeys = fragments.keys.sorted()
            for (key in sortedKeys) {
                val fragment = fragments[key]!!
                System.arraycopy(fragment, 0, mergedData, offset, fragment.size)
                offset += fragment.size
            }
        }

        return mergedData
    }

    /**
     * 清理设备相关数据
     */
    fun clearDeviceFragments(deviceAddress: String) {
        fragmentBuffersByDevice.remove(deviceAddress)
        expectedFragmentCountsByDevice.remove(deviceAddress)
    }
}
