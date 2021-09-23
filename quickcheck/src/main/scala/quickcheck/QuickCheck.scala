package quickcheck

import common._
import org.scalacheck._
import Arbitrary._
import Gen._
import Prop._

abstract class QuickCheckHeap extends Properties("Heap") with IntHeap {

  property("min1") = forAll { a: Int =>
    val h = insert(a, empty)
    findMin(h) == a
  }

  lazy val genHeap: Gen[H] = for {
    v <- arbitrary[Int]
    h <- oneOf(const(empty),genHeap )
  } yield insert(v, h)

  implicit lazy val arbHeap: Arbitrary[H] = Arbitrary(genHeap)
  
  property("gen1") = forAll { (h: H) =>
  	val m = if (isEmpty(h)) 0 else findMin(h)
  	findMin(insert(m, h))==m
  }
  
  /*If you insert any two elements into an empty heap,
   *  finding the minimum of the resulting heap should get the smallest of the two elements back.*/
  property("insert 2") = forAll {(a :Int, b :Int) => 
    val baHeap = insert(b, insert(a, empty))
    val abHeap = insert(a, insert(b, empty))
    if( a < b ){
      findMin(baHeap) == a && findMin(abHeap) == a
    } else {
      findMin(baHeap) == b && findMin(abHeap) == b
    }
  }
  
  /*If you insert an element into an empty heap, then delete the minimum, the resulting heap should be empty.*/
  property("insert delete") = forAll { a :Int =>
    isEmpty(deleteMin(insert(a, empty)))
  }
  
  /*Given any heap, you should get a sorted sequence of elements when continually finding and deleting min*/
  property("sorted sequence") = forAll{ h :H =>
    def pop(h :H, low :Int): Boolean ={
      val min = findMin(h)
      val newHeap = deleteMin(h)
      low <= min && (isEmpty(newHeap) || pop(newHeap, min))
    }
    isEmpty(h) || pop(h, Int.MinValue)
  }

  /*Finding a minimum of the melding of any two heaps should return a minimum of one or the other.*/
  property("melded findMin") = forAll{(h1 :H, h2 :H) =>
    val mel = meld(h1, h2)
    val min = findMin(mel)
    min == findMin(h1) || min == findMin(h2)
  }
  
  /*When inserting a list performing a check on pop*/
   property("insert list and check") = forAll { l: List[Int] =>
    def pop(h: H, l: List[Int]): Boolean = {
      if (isEmpty(h)) {
        l.isEmpty
      } else {
        findMin(h) == l.head && pop(deleteMin(h), l.tail)
      }
    }
    val sl = l.sorted
    val h = l.foldLeft(empty)((he, a) => insert(a, he))
    pop(h, sl)
  }
}
