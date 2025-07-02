# GeoMesa DynamoDB

GeoMesa DynamoDB is a distributed, geospatial database built on Amazon DynamoDB. It provides spatio-temporal indexing and querying capabilities using DynamoDB's native features for high performance and scalability.

## Features

- **AWS SDK v2**: Built with the latest AWS SDK for Java v2 for improved performance and features
- **IAM Integration**: Full support for IAM roles, profiles, and assume role functionality
- **Retry Logic**: Built-in exponential backoff retry logic for handling throttling and transient errors
- **Batch Operations**: Optimized batch read/write operations for improved throughput
- **Flexible Billing**: Support for both pay-per-request and provisioned billing modes
- **Local Testing**: Support for DynamoDB Local for development and testing

## Quick Start

### Prerequisites

- Java 11 or later
- AWS credentials configured (via AWS CLI, environment variables, or IAM roles)
- DynamoDB tables (can be created automatically or manually)

### Installation

Add the following dependency to your Maven project:

```xml
<dependency>
    <groupId>org.locationtech.geomesa</groupId>
    <artifactId>geomesa-dynamodb-datastore_2.12</artifactId>
    <version>5.4.0-SNAPSHOT</version>
</dependency>
```

### Configuration

#### Basic Configuration

```java
Map<String, String> params = new HashMap<>();
params.put("dynamodb.catalog", "my-catalog");
params.put("dynamodb.region", "us-east-1");

DataStore dataStore = DataStoreFinder.getDataStore(params);
```

#### Advanced Configuration

```java
Map<String, String> params = new HashMap<>();
params.put("dynamodb.catalog", "my-catalog");
params.put("dynamodb.region", "us-east-1");
params.put("dynamodb.table.prefix", "prod-");
params.put("dynamodb.billing.mode", "PROVISIONED");
params.put("dynamodb.read.capacity", "10");
params.put("dynamodb.write.capacity", "10");
params.put("dynamodb.assume.role.arn", "arn:aws:iam::123456789012:role/GeoMesaRole");
params.put("dynamodb.max.retries", "5");
params.put("dynamodb.batch.size", "25");

DataStore dataStore = DataStoreFinder.getDataStore(params);
```

## Configuration Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| `dynamodb.catalog` | Catalog name for the data store | Yes | - |
| `dynamodb.region` | AWS region | Yes | - |
| `dynamodb.endpoint` | DynamoDB endpoint URL (for local testing) | No | - |
| `dynamodb.table.prefix` | Prefix for DynamoDB table names | No | "" |
| `dynamodb.billing.mode` | Billing mode: PAY_PER_REQUEST or PROVISIONED | No | PAY_PER_REQUEST |
| `dynamodb.read.capacity` | Read capacity units (PROVISIONED mode only) | No | 5 |
| `dynamodb.write.capacity` | Write capacity units (PROVISIONED mode only) | No | 5 |
| `dynamodb.assume.role.arn` | IAM role ARN to assume | No | - |
| `dynamodb.profile` | AWS profile name | No | - |
| `dynamodb.max.retries` | Maximum retry attempts | No | 3 |
| `dynamodb.retry.delay` | Base retry delay | No | 100ms |
| `dynamodb.batch.size` | Batch operation size | No | 25 |
| `dynamodb.connection.timeout` | Connection timeout | No | 30s |
| `dynamodb.socket.timeout` | Socket timeout | No | 30s |
| `dynamodb.create.tables` | Auto-create tables | No | true |

## Table Management

### Automatic Table Creation

By default, GeoMesa will automatically create the necessary DynamoDB tables when you create a new feature type. This includes:

- Metadata table: `{prefix}{catalog}_metadata`
- Lock table: `{prefix}{catalog}_locks`
- Index tables: `{prefix}{catalog}_{typename}_{index}`

### Manual Table Creation

You can create tables manually using the provided script:

```bash
# Basic table creation
./create-dynamodb-tables.sh --catalog my-catalog --region us-east-1

# With custom settings
./create-dynamodb-tables.sh \
  --catalog my-catalog \
  --region us-east-1 \
  --table-prefix prod- \
  --billing-mode PROVISIONED \
  --read-capacity 10 \
  --write-capacity 10
```

### Using GeoMesa Tools

```bash
# Create tables using GeoMesa tools
geomesa-dynamodb create-tables \
  --catalog my-catalog \
  --region us-east-1 \
  --type-name my-feature-type
```

## Authentication

### AWS Credentials

GeoMesa DynamoDB supports multiple authentication methods:

1. **Environment Variables**:
   ```bash
   export AWS_ACCESS_KEY_ID=your-access-key
   export AWS_SECRET_ACCESS_KEY=your-secret-key
   export AWS_REGION=us-east-1
   ```

2. **AWS Profile**:
   ```java
   params.put("dynamodb.profile", "my-profile");
   ```

3. **IAM Roles** (for EC2/ECS/Lambda):
   - Automatically detected when running on AWS services

4. **Assume Role**:
   ```java
   params.put("dynamodb.assume.role.arn", "arn:aws:iam::123456789012:role/MyRole");
   ```

### Required IAM Permissions

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "dynamodb:CreateTable",
                "dynamodb:DescribeTable",
                "dynamodb:DeleteTable",
                "dynamodb:PutItem",
                "dynamodb:GetItem",
                "dynamodb:UpdateItem",
                "dynamodb:DeleteItem",
                "dynamodb:Query",
                "dynamodb:Scan",
                "dynamodb:BatchGetItem",
                "dynamodb:BatchWriteItem"
            ],
            "Resource": [
                "arn:aws:dynamodb:*:*:table/your-table-prefix*"
            ]
        }
    ]
}
```

## Performance Optimization

### Batch Operations

Configure batch size for optimal throughput:

```java
params.put("dynamodb.batch.size", "25"); // DynamoDB maximum
```

### Retry Configuration

Configure retry behavior for handling throttling:

```java
params.put("dynamodb.max.retries", "5");
params.put("dynamodb.retry.delay", "100ms");
```

### Billing Mode Selection

- **PAY_PER_REQUEST**: Best for unpredictable workloads
- **PROVISIONED**: Best for predictable workloads with cost optimization

## Local Development

### DynamoDB Local

For local development and testing:

1. **Start DynamoDB Local**:
   ```bash
   docker run -p 8000:8000 amazon/dynamodb-local
   ```

2. **Configure GeoMesa**:
   ```java
   params.put("dynamodb.endpoint", "http://localhost:8000");
   params.put("dynamodb.region", "us-east-1"); // Required even for local
   ```

3. **Create tables**:
   ```bash
   ./create-dynamodb-tables.sh \
     --catalog test-catalog \
     --region us-east-1 \
     --endpoint http://localhost:8000
   ```

## Monitoring and Troubleshooting

### Logging

Enable debug logging for troubleshooting:

```xml
<logger name="org.locationtech.geomesa.dynamodb" level="DEBUG"/>
```

### CloudWatch Metrics

Monitor DynamoDB performance using CloudWatch:
- Read/Write capacity utilization
- Throttled requests
- System errors

### Common Issues

1. **Throttling**: Increase capacity or enable auto-scaling
2. **Hot partitions**: Review partition key design
3. **Large items**: Consider compression or item splitting

## Examples

### Creating a Feature Type

```java
SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
builder.setName("locations");
builder.add("name", String.class);
builder.add("geom", Point.class, DefaultGeographicCRS.WGS84);
builder.add("dtg", Date.class);
SimpleFeatureType sft = builder.buildFeatureType();

dataStore.createSchema(sft);
```

### Writing Features

```java
FeatureWriter<SimpleFeatureType, SimpleFeature> writer = 
    dataStore.getFeatureWriterAppend("locations", Transaction.AUTO_COMMIT);

SimpleFeature feature = writer.next();
feature.setAttribute("name", "Test Location");
feature.setAttribute("geom", geometryFactory.createPoint(new Coordinate(-77.036, 38.895)));
feature.setAttribute("dtg", new Date());
writer.write();
writer.close();
```

### Querying Features

```java
Query query = new Query("locations", 
    CQL.toFilter("BBOX(geom, -78, 38, -76, 40) AND dtg DURING 2023-01-01T00:00:00.000Z/2023-12-31T23:59:59.999Z"));

FeatureReader<SimpleFeatureType, SimpleFeature> reader = 
    dataStore.getFeatureReader(query, Transaction.AUTO_COMMIT);

while (reader.hasNext()) {
    SimpleFeature feature = reader.next();
    System.out.println(feature);
}
reader.close();
```

## Building from Source

```bash
git clone https://github.com/locationtech/geomesa.git
cd geomesa/geomesa-dynamodb
mvn clean install -DskipTests
```

## Testing

Run tests with DynamoDB Local:

```bash
# Start DynamoDB Local
docker run -d -p 8000:8000 amazon/dynamodb-local

# Run tests
mvn test -Ddynamodb.endpoint=http://localhost:8000
```

## Contributing

Please see the main GeoMesa [CONTRIBUTING.md](../CONTRIBUTING.md) for guidelines on contributing to this project.

## License

GeoMesa DynamoDB is licensed under the Apache License, Version 2.0. See [LICENSE.txt](../LICENSE.txt) for details.
