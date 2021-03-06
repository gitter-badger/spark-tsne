package com.github.saurfang.spark.tsne.impl

import breeze.linalg._
import breeze.stats._
import breeze.stats.distributions.Rand
import com.github.saurfang.spark.tsne.{TSNEGradient, TSNEHelper, TSNEParam, X2P}
import org.apache.spark.Logging
import org.apache.spark.mllib.linalg.distributed.RowMatrix
import org.apache.spark.storage.StorageLevel
import rx.lang.scala.Observable

import scala.util.Random

object SimpleTSNE extends Logging {
  def tsne(
            input: RowMatrix,
            noDims: Int = 2,
            maxIterations: Int = 1000,
            perplexity: Double = 30,
            seed: Long = Random.nextLong()): Observable[(Int, DenseMatrix[Double], Option[Double])] = {
    if(input.rows.getStorageLevel == StorageLevel.NONE) {
      logWarning("Input is not persisted and performance could be bad")
    }

    Rand.generator.setSeed(seed)

    val tsneParam = TSNEParam()
    import tsneParam._

    val n = input.numRows().toInt
    val Y: DenseMatrix[Double] = DenseMatrix.rand(n, noDims, Rand.gaussian(0, 1))
    val iY = DenseMatrix.zeros[Double](n, noDims)
    val gains = DenseMatrix.ones[Double](n, noDims)

    // approximate p_{j|i}
    val p_ji = X2P(input, 1e-5, perplexity)
    val P = TSNEHelper.computeP(p_ji, n).glom().cache()

    Observable(subscriber => {
      var iteration = 1
      while(iteration <= maxIterations && !subscriber.isUnsubscribed) {
        val bcY = P.context.broadcast(Y)

        val numerator = P.map{ arr => TSNEGradient.computeNumerator(bcY.value, arr.map(_._1): _*) }.cache()
        val bcNumerator = P.context.broadcast({
          numerator.treeAggregate(0.0)(seqOp = (x, v) => x + sum(v), combOp = _ + _)
        })

        val (dY, loss) = P.zip(numerator).treeAggregate((DenseMatrix.zeros[Double](n, noDims), 0.0))(
          seqOp = (c, v) => {
            // c: (grad, loss), v: (Array[(i, Iterable(j, Distance))], numerator)
            val l = TSNEGradient.compute(v._1, bcY.value, v._2, bcNumerator.value, c._1, iteration <= early_exaggeration)
            (c._1, c._2 + l)
          },
          combOp = (c1, c2) => {
            // c: (grad, loss)
            (c1._1 + c2._1, c1._2 + c2._2)
          })

        bcY.destroy()
        bcNumerator.destroy()
        numerator.unpersist()

        TSNEHelper.update(Y, dY, iY, gains, iteration, tsneParam)

        logDebug(s"Iteration $iteration finished with $loss")
        subscriber.onNext((iteration, Y.copy, Some(loss)))
        iteration += 1
      }
      if(!subscriber.isUnsubscribed) subscriber.onCompleted()
    })
  }
}
