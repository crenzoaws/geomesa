#!/usr/bin/env bash
#
# Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
# All rights reserved. This program and the accompanying materials
# are made available under the terms of the Apache License, Version 2.0
# which accompanies this distribution and is available at
# http://www.opensource.org/licenses/apache2.0.php.
#
# Standalone script to create DynamoDB tables for GeoMesa
#

set -euo pipefail

# Default values
CATALOG=""
REGION=""
TABLE_PREFIX=""
BILLING_MODE="PAY_PER_REQUEST"
READ_CAPACITY=5
WRITE_CAPACITY=5
ENDPOINT=""
PROFILE=""
ASSUME_ROLE=""

# Function to display usage
usage() {
    cat << EOF
Usage: $0 [OPTIONS]

Create DynamoDB tables for GeoMesa

Required Options:
  --catalog CATALOG         Catalog name
  --region REGION          AWS region

Optional Options:
  --table-prefix PREFIX    Prefix for table names
  --billing-mode MODE      Billing mode: PAY_PER_REQUEST or PROVISIONED (default: PAY_PER_REQUEST)
  --read-capacity NUM      Read capacity units (default: 5, only for PROVISIONED mode)
  --write-capacity NUM     Write capacity units (default: 5, only for PROVISIONED mode)
  --endpoint URL           DynamoDB endpoint URL (for local testing)
  --profile PROFILE        AWS profile name
  --assume-role ARN        IAM role ARN to assume
  --help                   Show this help message

Examples:
  # Create tables with pay-per-request billing
  $0 --catalog my-catalog --region us-east-1

  # Create tables with provisioned billing
  $0 --catalog my-catalog --region us-east-1 --billing-mode PROVISIONED --read-capacity 10 --write-capacity 10

  # Create tables for local DynamoDB
  $0 --catalog my-catalog --region us-east-1 --endpoint http://localhost:8000

EOF
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --catalog)
            CATALOG="$2"
            shift 2
            ;;
        --region)
            REGION="$2"
            shift 2
            ;;
        --table-prefix)
            TABLE_PREFIX="$2"
            shift 2
            ;;
        --billing-mode)
            BILLING_MODE="$2"
            shift 2
            ;;
        --read-capacity)
            READ_CAPACITY="$2"
            shift 2
            ;;
        --write-capacity)
            WRITE_CAPACITY="$2"
            shift 2
            ;;
        --endpoint)
            ENDPOINT="$2"
            shift 2
            ;;
        --profile)
            PROFILE="$2"
            shift 2
            ;;
        --assume-role)
            ASSUME_ROLE="$2"
            shift 2
            ;;
        --help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            usage
            exit 1
            ;;
    esac
done

# Validate required parameters
if [[ -z "$CATALOG" ]]; then
    echo "Error: --catalog is required"
    usage
    exit 1
fi

if [[ -z "$REGION" ]]; then
    echo "Error: --region is required"
    usage
    exit 1
fi

# Validate billing mode
if [[ "$BILLING_MODE" != "PAY_PER_REQUEST" && "$BILLING_MODE" != "PROVISIONED" ]]; then
    echo "Error: --billing-mode must be either PAY_PER_REQUEST or PROVISIONED"
    exit 1
fi

# Set AWS CLI options
AWS_OPTS="--region $REGION"
if [[ -n "$PROFILE" ]]; then
    AWS_OPTS="$AWS_OPTS --profile $PROFILE"
fi
if [[ -n "$ENDPOINT" ]]; then
    AWS_OPTS="$AWS_OPTS --endpoint-url $ENDPOINT"
fi

# Function to create a table
create_table() {
    local table_name="$1"
    local pk_name="$2"
    local sk_name="$3"
    
    echo "Creating table: $table_name"
    
    # Build the create-table command
    local create_cmd="aws dynamodb create-table $AWS_OPTS --table-name $table_name"
    create_cmd="$create_cmd --attribute-definitions AttributeName=$pk_name,AttributeType=S"
    
    if [[ -n "$sk_name" ]]; then
        create_cmd="$create_cmd AttributeName=$sk_name,AttributeType=S"
        create_cmd="$create_cmd --key-schema AttributeName=$pk_name,KeyType=HASH AttributeName=$sk_name,KeyType=RANGE"
    else
        create_cmd="$create_cmd --key-schema AttributeName=$pk_name,KeyType=HASH"
    fi
    
    create_cmd="$create_cmd --billing-mode $BILLING_MODE"
    
    if [[ "$BILLING_MODE" == "PROVISIONED" ]]; then
        create_cmd="$create_cmd --provisioned-throughput ReadCapacityUnits=$READ_CAPACITY,WriteCapacityUnits=$WRITE_CAPACITY"
    fi
    
    # Execute the command
    if eval "$create_cmd" >/dev/null 2>&1; then
        echo "  ✓ Table $table_name created successfully"
        
        # Wait for table to become active
        echo "  Waiting for table to become active..."
        aws dynamodb wait table-exists $AWS_OPTS --table-name "$table_name"
        echo "  ✓ Table $table_name is now active"
    else
        echo "  ⚠ Table $table_name may already exist or creation failed"
    fi
}

# Main execution
echo "Creating DynamoDB tables for GeoMesa"
echo "Catalog: $CATALOG"
echo "Region: $REGION"
echo "Table Prefix: ${TABLE_PREFIX:-<none>}"
echo "Billing Mode: $BILLING_MODE"
if [[ "$BILLING_MODE" == "PROVISIONED" ]]; then
    echo "Read Capacity: $READ_CAPACITY"
    echo "Write Capacity: $WRITE_CAPACITY"
fi
echo

# Create metadata table
create_table "${TABLE_PREFIX}${CATALOG}_metadata" "feature_type" "key"

# Create locks table
create_table "${TABLE_PREFIX}${CATALOG}_locks" "lock_key" ""

echo
echo "✓ All tables created successfully!"
echo
echo "You can now use these connection parameters in your GeoMesa configuration:"
echo "  dynamodb.catalog=$CATALOG"
echo "  dynamodb.region=$REGION"
if [[ -n "$TABLE_PREFIX" ]]; then
    echo "  dynamodb.table.prefix=$TABLE_PREFIX"
fi
if [[ -n "$ENDPOINT" ]]; then
    echo "  dynamodb.endpoint=$ENDPOINT"
fi
echo "  dynamodb.billing.mode=$BILLING_MODE"
if [[ "$BILLING_MODE" == "PROVISIONED" ]]; then
    echo "  dynamodb.read.capacity=$READ_CAPACITY"
    echo "  dynamodb.write.capacity=$WRITE_CAPACITY"
fi
