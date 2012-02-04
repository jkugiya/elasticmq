package org.elasticmq.performance

import org.elasticmq.{NodeBuilder, Queue}

object MultiThreadPerformanceTest {
  def main(args: Array[String]) {
    val numberOfThreads = 5
    val messageCount = 100

    //val node = NodeBuilder.withInMemoryStorage().build()
    //val storageName = "InMemory"

    //val node = NodeBuilder.withMySQLStorage("elasticmq", "root", "").build()
    //val storageName = "MySQL"

    //val node = NodeBuilder.withH2InMemoryStorage().build()
    //val storageName = "H2"
    
    val client = node.nativeClient
    val testQueue = client.lookupOrCreateQueue("perfTest")

    // warm up
    run(storageName, testQueue, 1, 1000)

    run(storageName, testQueue, numberOfThreads, messageCount)

    node.shutdown()
  }

  def run(storageName: String, queue: Queue, numberOfThreads: Int, messageCount: Int) {
    val sendTook = timeRunAndJoinThreads(numberOfThreads, () => new SendMessages(queue, messageCount))
    val receiveTook = timeRunAndJoinThreads(numberOfThreads, () => new ReceiveMessages(queue, messageCount))

    val ops = messageCount*numberOfThreads

    println("Storage: %s, number of threads: %d, number of messages: %d".format(storageName, numberOfThreads, messageCount))
    printStats("Send", sendTook, ops)
    printStats("Receive", receiveTook, ops)
    println()
  }
  
  def printStats(name: String, took: Long, ops: Int) {
    val seconds = took/1000L
    println("%s took: %d (%d), ops: %d, ops per second: %d".format(name, seconds, took, ops,
      if (seconds == 0) ops else ops/seconds))
  } 
  
  def timeRunAndJoinThreads(numberOfThreads: Int, runnable: () => Runnable) = {
    timed {
      val threads = for (i <- 1 to numberOfThreads) yield {
        val t = new Thread(runnable())
        t.start()
        t
      }

      threads.foreach(_.join())
    }
  }

  def timed[T](block: => Unit) = {
    val start = System.currentTimeMillis()
    block
    val end = System.currentTimeMillis()

    end-start
  }

  class SendMessages(queue: Queue, count: Int) extends Runnable {
    def run() {
      var i = 0;
      while (i < count) {
        queue.sendMessage("message"+i)
        i += 1
      }
    }
  }

  class ReceiveMessages(queue: Queue, count: Int) extends Runnable {
    def run() {
      var i = 0
      while (i < count) {
        val msgOpt = queue.receiveMessage()
        assert(msgOpt != None)
        msgOpt.map(_.delete())
        i += 1
      }
    }
  }
}