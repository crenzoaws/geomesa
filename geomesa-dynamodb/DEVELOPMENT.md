# GeoMesa DynamoDB Development Guide

This guide covers development setup, testing, and contribution guidelines for the GeoMesa DynamoDB module.

## Development Environment Setup

### Prerequisites

- Java 11 or later
- Maven 3.6.3 or later
- Docker (for local DynamoDB testing)
- AWS CLI (optional, for AWS integration testing)

### Local Development Setup

1. **Clone the repository**:
   ```bash
   git clone https://github.com/locationtech/geomesa.git
   cd geomesa/geomesa-dynamodb
   ```

2. **Start local DynamoDB**:
   ```bash
   docker-compose up -d
   ```
   
   This starts:
   - DynamoDB Local on port 8000
   - DynamoDB Admin UI on port 8001

3. **Build the project**:
   ```bash
   mvn clean compile
   ```

4. **Run tests**:
   ```bash
   mvn test
   ```

### IDE Setup

#### IntelliJ IDEA

1. Import the project as a Maven project
2. Set Project SDK to Java 11+
3. Enable Scala plugin
4. Configure code style to match GeoMesa standards

#### Eclipse

1. Import as Maven project
2. Install Scala IDE plugin
3. Configure build path and dependencies

## Project Structure

```
geomesa-dynamodb/
├── geomesa-dynamodb-datastore/     # Core datastore implementation
│   ├── src/main/scala/             # Main source code
│   │   └── org/locationtech/geomesa/dynamodb/data/
│   │       ├── DynamoDbDataStore.scala
│   │       ├── DynamoDbDataStoreFactory.scala
│   │       ├── package.scala       # Parameters and configuration
│   │       ├── index/              # Index implementations
│   │       └── util/               # Utility classes
│   ├── src/test/scala/             # Test source code
│   └── src/main/resources/         # Configuration files
├── geomesa-dynamodb-tools/         # Command-line tools
├── geomesa-dynamodb-gs-plugin/     # GeoServer plugin
├── geomesa-dynamodb-dist/          # Distribution assembly
└── docker-compose.yml              # Local development environment
```

## Key Components

### Core Classes

- **DynamoDbDataStore**: Main datastore implementation
- **DynamoDbDataStoreFactory**: Factory for creating datastore instances
- **DynamoDbIndexAdapter**: Handles index operations
- **DynamoDbQueryPlan**: Query execution planning
- **DynamoDbFeatureWriter**: Feature writing operations

### Utility Classes

- **DynamoDbBackedMetadata**: Metadata storage in DynamoDB
- **DynamoDbLocking**: Distributed locking implementation
- **DynamoDbGeoMesaStats**: Statistics collection

## Testing

### Unit Tests

Run unit tests with:
```bash
mvn test
```

### Integration Tests

Integration tests require DynamoDB Local:

1. **Start DynamoDB Local**:
   ```bash
   docker-compose up -d dynamodb-local
   ```

2. **Run integration tests**:
   ```bash
   mvn test -Dtest=*IntegrationTest
   ```

### AWS Integration Tests

For testing against real AWS DynamoDB:

1. **Configure AWS credentials**:
   ```bash
   aws configure
   # or set environment variables
   export AWS_ACCESS_KEY_ID=your-key
   export AWS_SECRET_ACCESS_KEY=your-secret
   export AWS_REGION=us-east-1
   ```

2. **Run AWS tests**:
   ```bash
   mvn test -Dtest=*AwsTest -Daws.test.enabled=true
   ```

### Performance Tests

Performance tests can be run with:
```bash
mvn test -Dtest=*PerformanceTest -Dperformance.test.enabled=true
```

## Code Style and Standards

### Scala Style

- Follow standard Scala conventions
- Use 2-space indentation
- Maximum line length: 120 characters
- Use meaningful variable and method names

### Documentation

- Add ScalaDoc for public APIs
- Include usage examples in documentation
- Update README.md for significant changes

### Error Handling

- Use proper exception handling with meaningful messages
- Log errors appropriately with context
- Provide retry logic for transient failures

## Contributing

### Before Submitting

1. **Run all tests**:
   ```bash
   mvn clean test
   ```

2. **Check code style**:
   ```bash
   mvn scalastyle:check
   ```

3. **Build distribution**:
   ```bash
   mvn clean package
   ```

### Pull Request Guidelines

1. Create a feature branch from main
2. Make your changes with appropriate tests
3. Update documentation as needed
4. Ensure all tests pass
5. Submit pull request with clear description

### Commit Messages

Use clear, descriptive commit messages:
```
GEOMESA-XXXX: Add DynamoDB batch write optimization

- Implement batch write operations for improved throughput
- Add retry logic for handling throttling
- Update documentation with performance guidelines
```

## Debugging

### Enable Debug Logging

Add to your logback.xml or log4j configuration:
```xml
<logger name="org.locationtech.geomesa.dynamodb" level="DEBUG"/>
<logger name="software.amazon.awssdk.services.dynamodb" level="DEBUG"/>
```

### Common Issues

1. **Connection timeouts**: Check network connectivity and endpoint configuration
2. **Throttling errors**: Increase retry delays or provisioned capacity
3. **Authentication failures**: Verify AWS credentials and IAM permissions

### Profiling

Use JVM profiling tools to identify performance bottlenecks:
```bash
java -XX:+FlightRecorder -XX:StartFlightRecording=duration=60s,filename=profile.jfr ...
```

## Release Process

### Version Updates

1. Update version in all POM files
2. Update version references in documentation
3. Create release notes

### Building Release

```bash
mvn clean package -DskipTests
```

### Testing Release

1. Test with sample data
2. Verify all tools work correctly
3. Test GeoServer plugin integration

## AWS Best Practices

### Table Design

- Use appropriate partition keys for even distribution
- Consider access patterns when designing sort keys
- Use sparse indexes for optional attributes

### Performance Optimization

- Use batch operations when possible
- Implement exponential backoff for retries
- Monitor CloudWatch metrics

### Cost Optimization

- Choose appropriate billing mode
- Use auto-scaling for provisioned capacity
- Monitor and optimize unused capacity

## Troubleshooting

### Common Development Issues

1. **Maven dependency conflicts**: Use `mvn dependency:tree` to identify conflicts
2. **Scala compilation errors**: Ensure correct Scala version and dependencies
3. **Test failures**: Check DynamoDB Local is running and accessible

### AWS-Specific Issues

1. **IAM permissions**: Ensure proper DynamoDB permissions
2. **Region configuration**: Verify correct region settings
3. **Endpoint configuration**: Check endpoint URLs for local vs AWS

## Resources

- [GeoMesa Documentation](https://www.geomesa.org/documentation/)
- [AWS DynamoDB Documentation](https://docs.aws.amazon.com/dynamodb/)
- [AWS SDK for Java v2](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/)
- [Scala Documentation](https://docs.scala-lang.org/)
- [Maven Documentation](https://maven.apache.org/guides/)
