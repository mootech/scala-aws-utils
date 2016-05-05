package com.dwolla.awssdk

import com.amazonaws.AmazonWebServiceRequest
import com.amazonaws.regions.Regions
import com.amazonaws.regions.Regions.US_WEST_2
import com.amazonaws.services.cloudformation.AmazonCloudFormationAsyncClient
import com.amazonaws.services.cloudformation.model.Capability.CAPABILITY_IAM
import com.amazonaws.services.cloudformation.model.StackStatus._
import com.amazonaws.services.cloudformation.model.{Parameter ⇒ AwsParameter, _}
import com.dwolla.awssdk.utils.ScalaAsyncHandler

import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.language.implicitConversions

package object cloudformation {

  import concurrent.ExecutionContext.Implicits.global

  type StackID = String

  def clientForRegion(r: Regions) = {
    val x = new AmazonCloudFormationAsyncClient()
    x.configureRegion(r)
    x
  }

  def createOrUpdateTemplate(stackName: String, template: String, params: List[(String, String)] = List.empty[(String, String)]) = {
    val client = clientForRegion(US_WEST_2)

    def getStackByName(name: String) = withHandler[DescribeStacksRequest, DescribeStacksResult, Option[Stack]] { handler ⇒
      client.describeStacksAsync(handler)
      handler.future.map(_.getStacks.filter(s ⇒ s.getStackName == name && StackStatus.valueOf(s.getStackStatus) != DELETE_COMPLETE).toList.headOption)
    }

    def createStack(potentialStack: PotentialStack) = withHandler[CreateStackRequest, CreateStackResult, StackID] { handler ⇒
      client.createStackAsync(potentialStack, handler)
      handler.future.map(_.getStackId)
    }

    def updateStack(potentialStack: PotentialStack) = withHandler[UpdateStackRequest, UpdateStackResult, StackID] { handler ⇒
      client.updateStackAsync(potentialStack, handler)
      handler.future.map(_.getStackId)
    }

    implicit def tuplesToParams(tuples: List[(String, String)]): List[AwsParameter] = tuples.map {
      case (key, value) ⇒ new AwsParameter().withParameterKey(key).withParameterValue(value)
    }
    implicit def potentialStackToCreate(potentialStack: PotentialStack): CreateStackRequest = potentialStack.toCreateRequest
    implicit def potentialStackToUpdate(potentialStack: PotentialStack): UpdateStackRequest = potentialStack.toUpdateRequest
    implicit def stackStatus(status: String): StackStatus = StackStatus.valueOf(status)

    def withHandler[A <: AmazonWebServiceRequest, B, R](f: ScalaAsyncHandler[A, B] ⇒ Future[R]) = f(new ScalaAsyncHandler[A, B])

    val potentialStack = PotentialStack(stackName, template, params)

    try {
      Await.result(
        getStackByName(stackName).flatMap(
          _.fold(createStack(potentialStack)) { stack ⇒
            if (updatableStackStatuses.contains(stackStatus(stack.getStackStatus))) updateStack(potentialStack)
            else throw StackNotUpdatableException(stack.getStackName, stack.getStackStatus)
          }
        ), Duration.Inf)
    } finally {
      client.shutdown()
    }
  }

  private case class PotentialStack(name: String, template: String, parameters: List[AwsParameter]) {
    def toCreateRequest: CreateStackRequest = new CreateStackRequest()
      .withStackName(name)
      .withTemplateBody(template)
      .withParameters(parameters)
      .withCapabilities(CAPABILITY_IAM)

    def toUpdateRequest: UpdateStackRequest = new UpdateStackRequest()
      .withStackName(name)
      .withTemplateBody(template)
      .withParameters(parameters)
      .withCapabilities(CAPABILITY_IAM)
  }

  val updatableStackStatuses = Seq(
    CREATE_COMPLETE,
    ROLLBACK_COMPLETE,
    UPDATE_COMPLETE,
    UPDATE_ROLLBACK_COMPLETE
  )
}