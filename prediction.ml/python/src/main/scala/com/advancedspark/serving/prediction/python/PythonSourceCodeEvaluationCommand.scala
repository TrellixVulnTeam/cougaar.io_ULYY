package com.advancedspark.serving.prediction.python

import scala.collection.JavaConversions._
import scala.collection.JavaConverters.asScalaBufferConverter
import scala.collection.JavaConverters.mapAsJavaMapConverter

import com.netflix.hystrix.HystrixCommand
import com.netflix.hystrix.HystrixCommandGroupKey
import com.netflix.hystrix.HystrixCommandKey
import com.netflix.hystrix.HystrixThreadPoolKey
import com.netflix.hystrix.HystrixCommandProperties
import com.netflix.hystrix.HystrixThreadPoolProperties
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.stream.Collectors

class PythonSourceCodeEvaluationCommand(commandName: String, 
                                        namespace: String, 
                                        version:String, 
                                        filename: String, 
                                        inputJson: String,
    fallback: String, timeout: Int, concurrencyPoolSize: Int, rejectionThreshold: Int)
  extends HystrixCommand[String](
    HystrixCommand.Setter
      .withGroupKey(HystrixCommandGroupKey.Factory.asKey(commandName))
      .andCommandKey(HystrixCommandKey.Factory.asKey(commandName))
      .andThreadPoolKey(HystrixThreadPoolKey.Factory.asKey(commandName))
      .andCommandPropertiesDefaults(
        HystrixCommandProperties.Setter()
         .withExecutionTimeoutInMilliseconds(timeout)
         .withExecutionIsolationStrategy(HystrixCommandProperties.ExecutionIsolationStrategy.SEMAPHORE)
         .withExecutionIsolationSemaphoreMaxConcurrentRequests(concurrencyPoolSize)
         .withFallbackIsolationSemaphoreMaxConcurrentRequests(rejectionThreshold)
      )
      .andThreadPoolPropertiesDefaults(
        HystrixThreadPoolProperties.Setter()
          .withCoreSize(concurrencyPoolSize)
          .withQueueSizeRejectionThreshold(rejectionThreshold)
      )
    )
{
  def run(): String = {
    try{
      // TODO:  Improve this
      val p = Runtime.getRuntime().exec(s"python -W ignore store/${namespace}/${version}/${filename} '${inputJson}'")
      //System.out.println("p: " + p)
      
      val stdInput = new BufferedReader(new InputStreamReader(p.getInputStream()));
      //System.out.println("stdInput: " + stdInput)

      val stdError = new BufferedReader(new InputStreamReader(p.getErrorStream()));
      //System.out.println("stdError: " + stdError)

      // read the output from the command
      val success = stdInput.lines().collect(Collectors.joining("\n"))
      //System.out.println("success: " + success)

      val error = stdError.lines().collect(Collectors.joining("\n"))
      //System.out.println("error: " + error)

      
      var result = s"""{"result":"${success}""""
      
      if (error.length() > 0) {
        System.out.println("error: " + error)
        result = result + s""", "error":"${error}""""
      }
      
      result + "}"       
    } catch { 
       case e: Throwable => {
         System.out.println(e)
         throw e
       }
    }
  }

  override def getFallback(): String = {
    s"""${fallback}"""
  }
}