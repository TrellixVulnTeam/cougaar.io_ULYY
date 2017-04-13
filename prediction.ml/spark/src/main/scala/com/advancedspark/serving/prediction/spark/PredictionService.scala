package com.advancedspark.serving.prediction.spark

import java.io.FileOutputStream

import scala.collection.immutable.HashMap
import scala.io.Source
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.cloud.netflix.hystrix.EnableHystrix
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

import com.soundcloud.prometheus.hystrix.HystrixPrometheusMetricsPublisher

import io.prometheus.client.hotspot.StandardExports
import io.prometheus.client.spring.boot.EnablePrometheusEndpoint
import io.prometheus.client.spring.boot.EnableSpringBootMetricsCollector
import javax.servlet.annotation.MultipartConfig
import java.io.InputStream
import scala.util.parsing.json.JSON

@SpringBootApplication
@RestController
@EnableHystrix
@EnablePrometheusEndpoint
@EnableSpringBootMetricsCollector
class PredictionService {
  val modelRegistry = new scala.collection.mutable.HashMap[String, Array[Byte]]

  HystrixPrometheusMetricsPublisher.register("prediction_tensorflow")
  new StandardExports().register()

  // curl -i -X POST -v -H "Transfer-Encoding: chunked" \
  //  -F "model=@tensorflow_inception_graph.pb" \
  //  http://[host]:[port]/update-spark/[namespace]/spark_linear_regression/[version]
  @RequestMapping(path=Array("/update-spark/{namespace}/{modelName}/{version}"),
                  method=Array(RequestMethod.POST))
  def updateSpark(@PathVariable("namespace") namespace: String,
                       @PathVariable("modelName") modelName: String, 
                       @PathVariable("version") version: String,
                       @RequestParam("model") model: MultipartFile): ResponseEntity[HttpStatus] = {

    var inputStream: InputStream = null

    try {
      // Get name of uploaded file.
      val filename = model.getOriginalFilename()
  
      // Path where the uploaded file will be stored.
      val filepath = new java.io.File(s"store/${namespace}/${modelName}/${version}")
      if (!filepath.isDirectory()) {
        filepath.mkdirs()
      }
  
      // This buffer will store the data read from 'model' multipart file
      inputStream = model.getInputStream()
  
      Files.copy(inputStream, Paths.get(s"store/${namespace}/${modelName}/${version}/${filename}"))
    } catch {
      case e: Throwable => {
        System.out.println(e)
        throw e
      }
    } finally {
      if (inputStream != null) {
        inputStream.close()
      }
    }

    new ResponseEntity(HttpStatus.OK)
  }

  // curl -i -X POST -v -H "Transfer-Encoding: chunked" \
  //  -F "input=@input.json" \
  //  http://[host]:[port]/evaluate-spark/[namespace]/spark_linear_regression/[version]
  @RequestMapping(path=Array("/evaluate-spark/{namespace}/{modelName}/{version}"),
                  method=Array(RequestMethod.POST),
                  produces=Array("application/json; charset=UTF-8"))
    def evaluateSpark(@PathVariable("namespace") namespace: String,
                      @PathVariable("modelName") modelName: String,
                      @PathVariable("version") version: String,
                      @RequestBody inputJson: String): String = {
    try {
      val inputs = JSON.parseFull(inputJson).get.asInstanceOf[Map[String,Any]]
    
      val results = new SparkEvaluationCommand(modelName, namespace, modelName, version, inputs, "fallback", 5000, 20, 10)
          .execute()
  
      s"""{"results":[${results}]}"""
    } catch {
      case e: Throwable => {
        System.out.println(e)
        throw e
      }
    }
  }
}

object PredictionServiceMain {
  def main(args: Array[String]): Unit = {
    SpringApplication.run(classOf[PredictionService])
  }
}
