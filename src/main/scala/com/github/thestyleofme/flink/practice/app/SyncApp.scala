package com.github.thestyleofme.flink.practice.app

import com.github.thestyleofme.flink.practice.app.constansts.WriteTypeConstant
import com.github.thestyleofme.flink.practice.app.model.SyncConfig
import com.github.thestyleofme.flink.practice.app.udf.filter.SchemaAndTableFilter
import com.github.thestyleofme.flink.practice.app.udf.hbase.HBaseSink
import com.github.thestyleofme.flink.practice.app.udf.kafka.SyncKafkaSerializationSchema
import com.github.thestyleofme.flink.practice.app.utils.{CommonUtil, SyncJdbcUtil}
import com.github.thestyleofme.flink.practice.app.writers.{Es6Writer, HiveWriter, JdbcWriter, RedisWriter}
import com.google.gson.Gson
import com.typesafe.scalalogging.Logger
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.node.ObjectNode
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.connectors.kafka.{FlinkKafkaConsumer, FlinkKafkaProducer}
import org.apache.flink.streaming.util.serialization.JSONKeyValueDeserializationSchema
import org.slf4j.LoggerFactory

import java.util.Properties

/**
 * <p>
 * 基于canal的数据实时同步
 * --configFilePath src/main/resources/canal-sync-demo.json
 *
 * flink执行job示例：
 * /data/flink/flink-1.10.0/bin/flink run \
 * -m yarn-cluster \
 * -p 1 \
 * -yjm 1024m \
 * -ytm 4096m \
 * -ynm flink-test \
 * -c org.abigballofmud.flink.app.SyncApp \
 * /data/flink/flink-app-1.0-SNAPSHOT-jar-with-dependencies.jar \
 * --configFilePath hdfs://hdsp001:8020/data/flink_config_file/canal-sync-demo-cluster.json
 * </p>
 *
 * @author isacc 2020/02/25 14:55
 * @since 1.0
 */
//noinspection DuplicatedCode
object SyncApp {

  private val log = Logger(LoggerFactory.getLogger(SyncApp.getClass))
  val gson: Gson = new Gson()

  def main(args: Array[String]): Unit = {

    // 由于需要写hdfs有权限问题 这里造假当前用户
    System.setProperty("HADOOP_USER_NAME", "hive")
    System.setProperty("user.name", "hive")

    val env: StreamExecutionEnvironment = StreamExecutionEnvironment.getExecutionEnvironment
    // 获取flink执行配置
    val syncConfig: SyncConfig = CommonUtil.genSyncConfig(args)
    log.info("flink config file load success")
    // flink容错机制设置 如checkpoint、重启策略等
    CommonUtil.toleranceOption(env, syncConfig)
    val properties = new Properties()
    properties.setProperty("bootstrap.servers", syncConfig.sourceKafka.kafkaBootstrapServers)
    val kafkaConsumer: FlinkKafkaConsumer[ObjectNode] = new FlinkKafkaConsumer(
      syncConfig.sourceKafka.kafkaTopic,
      new JSONKeyValueDeserializationSchema(true),
      properties)
    log.info("starting read from kafka...")
    // 设置初始kafka读取的offset
    CommonUtil.initOffset(kafkaConsumer, syncConfig)
    val kafkaStream: DataStream[ObjectNode] = env.addSource(kafkaConsumer)
      .filter(new SchemaAndTableFilter(syncConfig))
    // sink
    kafkaToSink(syncConfig, kafkaStream)
    log.info("flink starting...")
    env.execute(syncConfig.syncFlink.jobName)
  }

  /**
   * kafka DataStream写入不同sink
   *
   * @param syncConfig  SyncConfig
   * @param kafkaStream DataStream[ObjectNode]
   */
  def kafkaToSink(syncConfig: SyncConfig, kafkaStream: DataStream[ObjectNode]): Unit = {
    syncConfig.syncFlink.writeType match {
      case WriteTypeConstant.JDBC =>
        // 根据字段类型生成sqlType
        SyncJdbcUtil.genSqlTypes(syncConfig)
        // 分流，若配置了upsert就分为两批，没有则三批
        val processedDataStream: DataStream[ObjectNode] = CommonUtil.splitDataStream(kafkaStream, syncConfig)
        JdbcWriter.doWrite(processedDataStream, syncConfig)
      case WriteTypeConstant.KAFKA =>
        // 写入另一个topic
        val properties = new Properties()
        properties.setProperty("bootstrap.servers", syncConfig.syncKafka.kafkaBootstrapServers)
        properties.setProperty("batch.size", "16384")
        kafkaStream.addSink(new FlinkKafkaProducer[ObjectNode](
          syncConfig.syncKafka.kafkaTopic,
          new SyncKafkaSerializationSchema(syncConfig.syncKafka.kafkaTopic),
          properties,
          FlinkKafkaProducer.Semantic.AT_LEAST_ONCE,
          3))
      case WriteTypeConstant.ELASTICSEARCH6 =>
        // 写入es
        Es6Writer.doWrite(syncConfig, kafkaStream)
      case WriteTypeConstant.REDIS =>
        // 写入redis
        RedisWriter.doWrite(syncConfig, kafkaStream)
      case WriteTypeConstant.HIVE =>
        // 通过写文件方式写入hive
        HiveWriter.doWrite(syncConfig, kafkaStream)
      case WriteTypeConstant.HBASE =>
        kafkaStream.addSink(new HBaseSink(syncConfig))
      case _ => throw new IllegalArgumentException("unsupported writeType")
    }
  }
}