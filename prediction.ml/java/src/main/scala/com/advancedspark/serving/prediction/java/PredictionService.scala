package com.advancedspark.serving.prediction.java

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.immutable.HashMap

import scala.collection.JavaConverters.asScalaBufferConverter
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.util.parsing.json.JSON

import org.jpmml.evaluator.Evaluator
import org.jpmml.evaluator.ModelEvaluatorFactory
import org.jpmml.model.ImportFilter
import org.jpmml.model.JAXBUtil
import org.xml.sax.InputSource

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot._
import org.springframework.boot.autoconfigure._
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.cloud.context.config.annotation._
import org.springframework.cloud.netflix.hystrix.EnableHystrix
import org.springframework.context.annotation._
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation._
import scala.util.{Try,Success,Failure}
import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.Stream
import java.util.stream.Collectors
import io.prometheus.client.spring.boot.EnablePrometheusEndpoint
import com.soundcloud.prometheus.hystrix.HystrixPrometheusMetricsPublisher
import io.prometheus.client.spring.boot.EnableSpringBootMetricsCollector
import io.prometheus.client.hotspot.StandardExports

@SpringBootApplication
@RestController
@EnableHystrix
@EnablePrometheusEndpoint
@EnableSpringBootMetricsCollector	
class PredictionService {
  val predictorRegistry = new scala.collection.mutable.HashMap[String, Predictable]
  
  HystrixPrometheusMetricsPublisher.register("prediction_java")
  new StandardExports().register()

  val responseHeaders = new HttpHeaders();

  @RequestMapping(path=Array("/update-java/{namespace}/{className}/{version}"),
                  method=Array(RequestMethod.POST)
                  //produces=Array("application/json; charset=UTF-8")
                  )
  def updateSource(@PathVariable("namespace") namespace: String, 
                   @PathVariable("className") className: String,
                   @PathVariable("version") version: String,
                   @RequestBody classSource: String): 
      ResponseEntity[String] = {
    Try {
      System.out.println(s"Generating source for ${namespace}/${className}/${version}:\n${classSource}")

      // Write the new java source to local disk
      val path = new java.io.File(s"store/${namespace}/${className}/${version}")
      if (!path.isDirectory()) {
        path.mkdirs()
      }

      val file = new java.io.File(s"store/${namespace}/${className}/${version}/${className}.java")
      if (!file.exists()) {
        file.createNewFile()
      }

      val fos = new java.io.FileOutputStream(file)
      fos.write(classSource.getBytes())

      val (predictor, generatedCode) = PredictorCodeGenerator.codegen(className, classSource)
      
      System.out.println(s"Updating cache for ${namespace}/${className}/${version}:\n${generatedCode}")
      
      // Update Predictor in Cache
      predictorRegistry.put(namespace + "/" + className + "/" + version, predictor)

      new ResponseEntity[String](generatedCode, responseHeaders, HttpStatus.OK)
    } match {
      case Failure(t: Throwable) => {
        val responseHeaders = new HttpHeaders();
        new ResponseEntity[String](s"""${t.getMessage}:\n${t.getStackTrace().mkString("\n")}""", responseHeaders,
          HttpStatus.BAD_REQUEST)
      }
      case Success(response) => response      
    }
  }
  
/*
    curl -i -X POST -v -H "Content-Type: application/json" \
      -d {"id":"21618"} \
      http://[hostname]:[port]/evaluate-java/default/java_equals/1
*/
  @RequestMapping(path=Array("/evaluate-java/{namespace}/{className}/{version}"),
                  method=Array(RequestMethod.POST),
                  produces=Array("application/json; charset=UTF-8"))
  def evaluateSource(@PathVariable("namespace") namespace: String, 
                     @PathVariable("className") className: String, 
                     @PathVariable("version") version: String,
                     @RequestBody inputJson: String): 
      ResponseEntity[String] = {
    Try {
      val predictorOption = predictorRegistry.get(namespace + "/" + className + "/" + version)

      val parsedInputOption = JSON.parseFull(inputJson)
      val inputs: Map[String, Any] = parsedInputOption match {
        case Some(parsedInput) => parsedInput.asInstanceOf[Map[String, Any]]
        case None => Map[String, Any]() 
      }

      val predictor = predictorOption match {
        case None => {
          val classFileName = s"store/${namespace}/${className}/${version}/${className}.java"

          //read file into stream
          val stream: Stream[String] = Files.lines(Paths.get(classFileName))
			    
          // reconstuct original
          val classSource = stream.collect(Collectors.joining("\n"))
          
          val (predictor, generatedCode) = PredictorCodeGenerator.codegen(className, classSource)

          System.out.println(s"Updating cache for ${namespace}/${className}/${version}:\n${generatedCode}")
      
          // Update Predictor in Cache
          predictorRegistry.put(namespace + "/" + className + "/" + version, predictor)
      
          System.out.println(s"Updating cache for ${namespace}/${className}/${version}:\n${generatedCode}")

          predictor
        }
        case Some(predictor) => {
           predictor
        }
      } 
          
      val result = new JavaSourceCodeEvaluationCommand(className, namespace, className, version, predictor, inputs, "fallback", 25, 20, 10).execute()

      new ResponseEntity[String](s"${result}", responseHeaders,
           HttpStatus.OK)
    } match {
      case Failure(t: Throwable) => {
        new ResponseEntity[String](s"""${t.getMessage}:\n${t.getStackTrace().mkString("\n")}""", responseHeaders,
          HttpStatus.BAD_REQUEST)
      }
      case Success(response) => response
    }   
  }
}

object PredictorCodeGenerator {
  def codegen(className: String, classSource: String): (Predictable, String) = {   
    val references = Map[String, Any]()

    val codeGenBundle = new CodeGenBundle(className,
        null, 
        Array(classOf[Initializable], classOf[Predictable], classOf[Serializable]), 
        Array(classOf[java.util.HashMap[String, Any]], classOf[java.util.Map[String, Any]]), 
        CodeFormatter.stripExtraNewLines(classSource)
    )
    
    Try {
      val clazz = CodeGenerator.compile(codeGenBundle)
      val generatedCode = CodeFormatter.format(codeGenBundle)

      System.out.println(s"\n${generatedCode}}")      
            
      val bar = clazz.newInstance().asInstanceOf[Initializable]
      bar.initialize(references)

      (bar.asInstanceOf[Predictable], generatedCode)
    } match {
      case Failure(t) => {
        System.out.println(s"Could not generate code: ${codeGenBundle}", t)
        throw t
      }
      case Success(tuple) => tuple
    }
  }
}

object PredictionServiceMain {
  def main(args: Array[String]): Unit = {
    SpringApplication.run(classOf[PredictionService])
  }
}
