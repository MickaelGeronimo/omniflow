#!/bin/bash
echo "=== Initializing LocalStack AWS Resources for OmniFlow ==="

export AWS_DEFAULT_REGION=us-east-1

# 1. Create S3 Bucket for Reconciliation Reports
echo "Creating S3 bucket: omniflow-reconciliation-reports"
awslocal s3 mb s3://omniflow-reconciliation-reports

# 2. Create SNS Topic
echo "Creating SNS Topic: omniflow-transactions-topic"
TOPIC_ARN=$(awslocal sns create-topic --name omniflow-transactions-topic --output text --query 'TopicArn')
echo "SNS Topic ARN: $TOPIC_ARN"

# 3. Create SQS Dead-Letter Queue (DLQ)
echo "Creating SQS DLQ: omniflow-settlement-dlq"
DLQ_URL=$(awslocal sqs create-queue --queue-name omniflow-settlement-dlq --output text --query 'QueueUrl')
DLQ_ARN=$(awslocal sqs get-queue-attributes --queue-url "$DLQ_URL" --attribute-names QueueArn --output text --query 'Attributes.QueueArn')
echo "DLQ ARN: $DLQ_ARN"

# 4. Create Main SQS Queue with Redrive Policy (maxReceiveCount = 3)
echo "Creating Main SQS Queue: omniflow-settlement-queue with DLQ Redrive Policy"
REDRIVE_POLICY="{\"deadLetterTargetArn\":\"$DLQ_ARN\",\"maxReceiveCount\":\"3\"}"
QUEUE_URL=$(awslocal sqs create-queue \
  --queue-name omniflow-settlement-queue \
  --attributes "{\"RedrivePolicy\":$(echo "$REDRIVE_POLICY" | sed 's/"/\\"/g')}" \
  --output text --query 'QueueUrl')
QUEUE_ARN=$(awslocal sqs get-queue-attributes --queue-url "$QUEUE_URL" --attribute-names QueueArn --output text --query 'Attributes.QueueArn')
echo "Main SQS Queue ARN: $QUEUE_ARN"

# 5. Subscribe Main SQS Queue to SNS Topic (Fan-Out Pattern)
echo "Subscribing SQS Queue to SNS Topic"
awslocal sns subscribe \
  --topic-arn "$TOPIC_ARN" \
  --protocol sqs \
  --notification-endpoint "$QUEUE_ARN"

echo "=== LocalStack Initialization Complete! ==="
