/*
 * Copyright 2010-2015 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://aws.amazon.com/apache2.0
 *
 * This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and
 * limitations under the License.
 */

package com.amazonaws.mobileconnectors.dynamodbv2.dynamodbmapper;

import static org.easymock.EasyMock.anyObject;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.mobileconnectors.dynamodbv2.dynamodbmapper.HashKeyAutoGenerated;
import com.amazonaws.mobileconnectors.dynamodbv2.dynamodbmapper.DynamoDBMapper.FailedBatch;
import com.amazonaws.mobileconnectors.dynamodbv2.dynamodbmapper.DynamoDBMapper.SaveObjectHandler;
import com.amazonaws.mobileconnectors.dynamodbv2.dynamodbmapper.DynamoDBMapperConfig.PaginationLoadingStrategy;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.AttributeValueUpdate;
import com.amazonaws.services.dynamodbv2.model.BatchGetItemRequest;
import com.amazonaws.services.dynamodbv2.model.BatchGetItemResult;
import com.amazonaws.services.dynamodbv2.model.BatchWriteItemRequest;
import com.amazonaws.services.dynamodbv2.model.BatchWriteItemResult;
import com.amazonaws.services.dynamodbv2.model.Condition;
import com.amazonaws.services.dynamodbv2.model.ExpectedAttributeValue;
import com.amazonaws.services.dynamodbv2.model.KeysAndAttributes;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.WriteRequest;
import com.amazonaws.util.StringUtils;

import org.easymock.Capture;
import org.easymock.CaptureType;
import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DynamoDBMapperTest {

    private AmazonDynamoDB mockClient;
    private DynamoDBMapper mapper;
    private final PaginationLoadingStrategy strategy = PaginationLoadingStrategy.LAZY_LOADING;
    private DynamoDBMapperConfig config;

    @Before
    public void setup() {
        config = new DynamoDBMapperConfig(strategy);
        mockClient = EasyMock.createMock(AmazonDynamoDBClient.class);
        mapper = new DynamoDBMapper(mockClient);
    }

    @Test
    public void testCreateKeyObjectTest() {
        IndexRangeKeyClass keyClass = mapper.createKeyObject(IndexRangeKeyClass.class,
                Long.parseLong("5"), Double.parseDouble("9"));
        assertEquals(keyClass.getKey(), 5L);
        assertEquals(keyClass.getRangeKey(), 9.0, .005);
    }

    @Test(expected = DynamoDBMappingException.class)
    public void testCreateKeyObjectTestNoHashKeyAnnotation() {
        TestClass keyClass = mapper.createKeyObject(TestClass.class, Long.parseLong("5"),
                Double.parseDouble("9"));
    }

    @Test(expected = DynamoDBMappingException.class)
    public void testCreateKeyObjectTestNoRangeKeyAnnotation() {
        MockTwoValuePlusVersionClass keyClass = mapper.createKeyObject(
                MockTwoValuePlusVersionClass.class, "Hash", "Range");
    }

    @Test
    public void testTransformAttributeUpdates() {

        mockClient = EasyMock.createMock(AmazonDynamoDBClient.class);
        mapper = new DynamoDBMapper(mockClient, config, new AttributeTransformer() {

            @Override
            public Map<String, AttributeValue> transform(Parameters<?> parameters) {
                Map<String, AttributeValue> upperCased = new HashMap<String, AttributeValue>();
                for (Map.Entry<String, AttributeValue> curr : parameters.getAttributeValues()
                        .entrySet()) {
                    upperCased.put(curr.getKey(),
                            new AttributeValue().withS(StringUtils.upperCase(curr.getValue().getS())));
                }
                return upperCased;
            }

            @Override
            public Map<String, AttributeValue> untransform(Parameters<?> parameters) {
                // TODO Auto-generated method stub
                return null;
            }

        });

        Map<String, AttributeValue> keys = new HashMap<String, AttributeValue>();
        keys.put("id", new AttributeValue().withS("hashKey"));
        Map<String, AttributeValueUpdate> updates = new HashMap<String, AttributeValueUpdate>();
        AttributeValueUpdate update = new AttributeValueUpdate().withValue(new AttributeValue()
                .withS("newValue1"));
        updates.put("firstValue", update);

        Map<String, AttributeValueUpdate> transformed = mapper.transformAttributeUpdates(
                MockTwoValuePlusVersionClass.class, "aws-android-sdk-dynamodbmapper-test", keys,
                updates, config);
        assertEquals(transformed.get("firstValue").getValue().getS(), "NEWVALUE1");
        assertNull(transformed.get("id"));
    }

    @Test
    public void testWriteOneBatchWithEntityTooLarge() {
        Map<String, List<WriteRequest>> batchMap = new HashMap<String, List<WriteRequest>>();
        List<WriteRequest> batchList = new ArrayList<WriteRequest>();
        WriteRequest wr1 = new WriteRequest();
        WriteRequest wr2 = new WriteRequest();
        WriteRequest wr3 = new WriteRequest();
        batchList.add(wr1);
        batchList.add(wr2);
        batchList.add(wr3);
        batchMap.put("testTable", batchList);
        EasyMock.reset(mockClient);

        AmazonServiceException ase = new AmazonServiceException("TestException");
        ase.setErrorCode("Request entity too large");

        BatchWriteItemResult mockResult = EasyMock.createMock(BatchWriteItemResult.class);
        EasyMock.reset(mockResult);
        EasyMock.expect(mockResult.getUnprocessedItems()).andReturn(
                new HashMap<String, List<WriteRequest>>()).times(2);
        // Will cause batches to be split and re-tried
        EasyMock.expect(mockClient.batchWriteItem(anyObject(BatchWriteItemRequest.class)))
                .andThrow(ase);
        EasyMock.expect(mockClient.batchWriteItem(anyObject(BatchWriteItemRequest.class)))
                .andReturn(mockResult);
        EasyMock.expect(mockClient.batchWriteItem(anyObject(BatchWriteItemRequest.class)))
                .andReturn(mockResult);
        EasyMock.replay(mockClient, mockResult);

        List<FailedBatch> result = mapper.writeOneBatch(batchMap);
        assertEquals(result.size(), 0);
        EasyMock.verify(mockClient);
    }

    @Test
    public void testBatchLoadRetiresForUnprocessedItems() {
        List<Object> itemsToGet = new ArrayList<Object>();
        itemsToGet.add(new MockTwoValuePlusVersionClass("PrimaryKey",
                "Value1", null));
        itemsToGet.add(new MockDifferentTableName("OtherPrimaryKey", "OtherValue1"));

        EasyMock.reset(mockClient);

        // First result will show that the first item was processed
        // successfully, and the second item needs to be retried
        BatchGetItemResult firstResult = new BatchGetItemResult();
        Map<String, List<Map<String, AttributeValue>>> responses = new HashMap<String, List<Map<String, AttributeValue>>>();

        List<Map<String, AttributeValue>> items = new ArrayList<Map<String, AttributeValue>>();

        Map<String, AttributeValue> item = new HashMap<String, AttributeValue>();
        item.put("id", new AttributeValue().withS("idValue"));
        item.put("firstValue", new AttributeValue().withS("firstValueValue"));
        item.put("secondValue", new AttributeValue().withS("secondValueValue"));
        item.put("version", new AttributeValue().withN("1"));

        items.add(item);
        responses.put(mapper.getTableName(MockTwoValuePlusVersionClass.class, config), items);

        firstResult.withResponses(responses);
        // Do Not process second table on first go around
        Map<String, KeysAndAttributes> unprocessedObjects = new HashMap<String, KeysAndAttributes>();
        KeysAndAttributes unprocessedObject = new KeysAndAttributes();
        Map<String, AttributeValue> unprocessedKey = new HashMap<String, AttributeValue>();
        unprocessedKey.put("id", new
                AttributeValue().withS("OtherPrimaryKey"));
        unprocessedObject.withKeys(unprocessedKey);
        unprocessedObjects.put("MockDifferentTableName", unprocessedObject);
        firstResult.withUnprocessedKeys(unprocessedObjects);

        // EasyMock is broken and will change all captured values to the last
        // capture, even if the capture
        // objects are different and set to CaptureType.ALL this is used so that
        // we can verify arguments
        FixedCapture<BatchGetItemRequest> capture = new FixedCapture<BatchGetItemRequest>(
                CaptureType.ALL, new FixedCapture.CapCallback<BatchGetItemRequest>() {

                    @Override
                    public void valueSet(BatchGetItemRequest value) {
                        assertEquals(value.getRequestItems().size(), 2);
                    }

                });
        EasyMock.expect(mockClient.batchGetItem(EasyMock.capture(capture))).andReturn(
                firstResult);

        BatchGetItemResult secondResult = new BatchGetItemResult();
        Map<String, List<Map<String, AttributeValue>>> secondResponses = new HashMap<String, List<Map<String, AttributeValue>>>();

        List<Map<String, AttributeValue>> secondItems = new ArrayList<Map<String, AttributeValue>>();

        Map<String, AttributeValue> secondItem = new HashMap<String, AttributeValue>();
        secondItem.put("id", new AttributeValue().withS("idValue2"));
        secondItem.put("firstValue", new AttributeValue().withS("firstValueValue2"));

        secondItems.add(secondItem);
        responses.put(mapper.getTableName(MockDifferentTableName.class, config), secondItems);
        secondResult.withResponses(secondResponses);
        FixedCapture<BatchGetItemRequest> capture2 = new FixedCapture<BatchGetItemRequest>(
                CaptureType.ALL, new FixedCapture.CapCallback<BatchGetItemRequest>() {

                    @Override
                    public void valueSet(BatchGetItemRequest value) {
                        assertEquals(value.getRequestItems().size(), 1);
                    }

                });

        EasyMock.expect(mockClient.batchGetItem(EasyMock.capture(capture2))).andReturn(
                secondResult);

        EasyMock.replay(mockClient);

        Map<String, List<Object>> loadResults = mapper.batchLoad(itemsToGet);

        EasyMock.verify(mockClient);
        assertEquals(loadResults.keySet().size(), 2);
        assertEquals(loadResults.get("aws-android-sdk-dynamodbmapper-test").size(), 1);
        assertEquals(loadResults.get("aws-android-sdk-dynamodbmapper-test-different-table").size(),
                1);
        assertEquals(loadResults.get("aws-android-sdk-dynamodbmapper-test").get(0).getClass(),
                MockTwoValuePlusVersionClass.class);
        assertEquals(loadResults.get("aws-android-sdk-dynamodbmapper-test-different-table").get(0)
                .getClass(), MockDifferentTableName.class);

    }

    @Test
    public void testMergeExpectedAttributeValueConditions() {
        Map<String, ExpectedAttributeValue> internalAssertions = new HashMap<String, ExpectedAttributeValue>();
        Map<String, ExpectedAttributeValue> userProvidedConditions = new HashMap<String, ExpectedAttributeValue>();

        ExpectedAttributeValue internal = new ExpectedAttributeValue()
                .withValue(new AttributeValue().withS("internal"));
        ExpectedAttributeValue user = new ExpectedAttributeValue().withValue(new AttributeValue()
                .withS("user"));
        ExpectedAttributeValue bothInterlan = new ExpectedAttributeValue()
                .withValue(new AttributeValue().withS("bothInterlan"));
        ExpectedAttributeValue bothUser = new ExpectedAttributeValue()
                .withValue(new AttributeValue().withS("bothUser"));

        internalAssertions.put("internal", internal);
        userProvidedConditions.put("user", user);
        internalAssertions.put("both", bothInterlan);
        userProvidedConditions.put("both", bothUser);

        Map<String, ExpectedAttributeValue> merged = DynamoDBMapper
                .mergeExpectedAttributeValueConditions(
                        internalAssertions, userProvidedConditions, "AND");
        assertEquals(merged.get("internal").getValue().getS(), "internal");
        assertEquals(merged.get("user").getValue().getS(), "user");
        assertEquals(merged.get("both").getValue().getS(), "bothUser");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMergeExpectedAttributeValueConditionsInvalidOperator() {
        Map<String, ExpectedAttributeValue> internalAssertions = new HashMap<String, ExpectedAttributeValue>();
        Map<String, ExpectedAttributeValue> userProvidedConditions = new HashMap<String, ExpectedAttributeValue>();

        ExpectedAttributeValue internal = new ExpectedAttributeValue()
                .withValue(new AttributeValue().withS("internal"));
        ExpectedAttributeValue user = new ExpectedAttributeValue().withValue(new AttributeValue()
                .withS("user"));
        ExpectedAttributeValue bothInterlan = new ExpectedAttributeValue()
                .withValue(new AttributeValue().withS("bothInterlan"));
        ExpectedAttributeValue bothUser = new ExpectedAttributeValue()
                .withValue(new AttributeValue().withS("bothUser"));

        internalAssertions.put("internal", internal);
        userProvidedConditions.put("user", user);
        internalAssertions.put("both", bothInterlan);
        userProvidedConditions.put("both", bothUser);

        Map<String, ExpectedAttributeValue> merged = DynamoDBMapper
                .mergeExpectedAttributeValueConditions(
                        internalAssertions, userProvidedConditions, "OR");
    }

    @Test
    public void testMergeExpectedAttributeValueConditionsNoInternalAssertions() {
        Map<String, ExpectedAttributeValue> userProvidedConditions = new HashMap<String, ExpectedAttributeValue>();

        ExpectedAttributeValue user = new ExpectedAttributeValue().withValue(new AttributeValue()
                .withS("user"));

        userProvidedConditions.put("user", user);

        Map<String, ExpectedAttributeValue> merged = DynamoDBMapper
                .mergeExpectedAttributeValueConditions(
                        null, userProvidedConditions, "AND");
        assertEquals(merged.get("user").getValue().getS(), "user");
        assertEquals(merged.size(), 1);
    }

    @Test
    public void testMergeExpectedAttributeValueConditionsNoUserProvidedConditions() {
        Map<String, ExpectedAttributeValue> internalAssertions = new HashMap<String, ExpectedAttributeValue>();

        ExpectedAttributeValue internal = new ExpectedAttributeValue()
                .withValue(new AttributeValue().withS("internal"));

        internalAssertions.put("internal", internal);

        Map<String, ExpectedAttributeValue> merged = DynamoDBMapper
                .mergeExpectedAttributeValueConditions(
                        internalAssertions, null, "AND");
        assertEquals(merged.get("internal").getValue().getS(), "internal");
        assertEquals(merged.size(), 1);
    }

    @Test
    public void testNeedAutoGenerateAssignableKey() {
        HashKeyAutoGenerated autoGenObj = new HashKeyAutoGenerated();
        assertTrue(mapper.needAutoGenerateAssignableKey(HashKeyAutoGenerated.class, autoGenObj));
        MockTwoValuePlusVersionClass nonAutogen = new MockTwoValuePlusVersionClass();
        assertFalse(mapper.needAutoGenerateAssignableKey(MockTwoValuePlusVersionClass.class,
                nonAutogen));
    }

    @Test
    public void testBatchLoadReturnsEmptyMapWithRequestOfNoObjects() {
        Map<String, List<Object>> result = mapper.batchLoad(new ArrayList<Object>());
        assertEquals(result.keySet().size(), 0);
    }

    @Test
    public void testCreateScanRequestFromExpression() {

        DynamoDBScanExpression se = new DynamoDBScanExpression();
        se.setConditionalOperator("lt");
        Map<String, AttributeValue> esk = new HashMap<String, AttributeValue>();
        se.setExclusiveStartKey(esk);
        Map<String, String> ean = new HashMap<String, String>();
        se.setExpressionAttributeNames(ean);
        Map<String, AttributeValue> eav = new HashMap<String, AttributeValue>();
        se.setExpressionAttributeValues(eav);
        se.setFilterExpression("testFilter");
        se.setLimit(5);
        Map<String, Condition> filter = new HashMap<String, Condition>();
        se.setScanFilter(filter);
        se.setSegment(2);
        se.setTotalSegments(10);

        ScanRequest sr = mapper.createScanRequestFromExpression(MockTwoValuePlusVersionClass.class,
                se, config);

        assertEquals(sr.getConditionalOperator(), "lt");
        assertEquals(sr.getTableName(), "aws-android-sdk-dynamodbmapper-test");
        assertEquals(sr.getExclusiveStartKey(), esk);
        assertEquals(sr.getExpressionAttributeNames(), ean);
        assertEquals(sr.getExpressionAttributeValues(), eav);
        assertEquals(sr.getFilterExpression(), "testFilter");
        assertEquals(sr.getLimit().intValue(), 5);
        assertEquals(sr.getScanFilter(), filter);
        assertEquals(sr.getSegment().intValue(), 2);
        assertEquals(sr.getTotalSegments().intValue(), 10);
    }

    @Test
    public void createParalellScanRequestsFromExpression() {
        DynamoDBScanExpression se = new DynamoDBScanExpression();
        se.setConditionalOperator("lt");
        Map<String, AttributeValue> esk = new HashMap<String, AttributeValue>();
        se.setExclusiveStartKey(esk);
        Map<String, String> ean = new HashMap<String, String>();
        se.setExpressionAttributeNames(ean);
        Map<String, AttributeValue> eav = new HashMap<String, AttributeValue>();
        se.setExpressionAttributeValues(eav);
        se.setFilterExpression("testFilter");
        se.setLimit(5);
        Map<String, Condition> filter = new HashMap<String, Condition>();
        se.setScanFilter(filter);
        se.setSegment(2);
        se.setTotalSegments(10);

        List<ScanRequest> requests = mapper.createParallelScanRequestsFromExpression(
                MockTwoValuePlusVersionClass.class,
                se, 2, config);

        ScanRequest sr1 = requests.get(0);

        assertEquals(sr1.getConditionalOperator(), "lt");
        assertEquals(sr1.getTableName(), "aws-android-sdk-dynamodbmapper-test");
        assertNull(sr1.getExclusiveStartKey());
        assertEquals(sr1.getExpressionAttributeNames(), ean);
        assertEquals(sr1.getExpressionAttributeValues(), eav);
        assertEquals(sr1.getFilterExpression(), "testFilter");
        assertEquals(sr1.getLimit().intValue(), 5);
        assertEquals(sr1.getScanFilter(), filter);
        assertEquals(sr1.getSegment().intValue(), 0);
        assertEquals(sr1.getTotalSegments().intValue(), 2);

        ScanRequest sr2 = requests.get(1);

        assertEquals(sr2.getConditionalOperator(), "lt");
        assertEquals(sr2.getTableName(), "aws-android-sdk-dynamodbmapper-test");
        assertNull(sr2.getExclusiveStartKey());
        assertEquals(sr2.getExpressionAttributeNames(), ean);
        assertEquals(sr2.getExpressionAttributeValues(), eav);
        assertEquals(sr2.getFilterExpression(), "testFilter");
        assertEquals(sr2.getLimit().intValue(), 5);
        assertEquals(sr2.getScanFilter(), filter);
        assertEquals(sr2.getSegment().intValue(), 1);
        assertEquals(sr2.getTotalSegments().intValue(), 2);
    }

    @Test
    public void testContainsThrottlingException() {
        List<FailedBatch> failedBatches = new ArrayList<FailedBatch>();

        FailedBatch nonThrottle = new FailedBatch();
        nonThrottle.setException(new AmazonServiceException("InvalidInput"));
        failedBatches.add(nonThrottle);

        assertFalse(mapper.containsThrottlingException(failedBatches));

        FailedBatch throttle = new FailedBatch();
        AmazonServiceException ase = new AmazonServiceException("ThrottlingException");
        ase.setErrorCode("ThrottlingException");
        nonThrottle.setException(ase);
        failedBatches.add(throttle);

        assertTrue(mapper.containsThrottlingException(failedBatches));
    }

    @Test
    public void testSaveObjectHandler() {

        MockTwoValuePlusVersionClass mockClass = new MockTwoValuePlusVersionClass("PrimaryKey",
                "Value1", null);
        String tableName = mapper.getTableName(MockTwoValuePlusVersionClass.class, mockClass,
                config);

        final Map<String, Boolean> expectedFound = new HashMap<String, Boolean>();
        final String foundNullAttributeKey = "NullAttribute";
        final String foundKeyKey = "Key";

        SaveObjectHandler testHandler = mapper.new SaveObjectHandler(
                MockTwoValuePlusVersionClass.class, mockClass,
                tableName, config, mapper.getConverter(config), null) {

            @Override
            protected void onKeyAttributeValue(String attributeName,
                    AttributeValue keyAttributeValue) {
                if (attributeName.equalsIgnoreCase("id")
                        && keyAttributeValue.getS().equalsIgnoreCase("PrimaryKey")) {
                    expectedFound.put(foundKeyKey, true);
                } else {
                    fail("Incorrect onKeyAttributeValueCalled --- received attributeName: "
                            + attributeName + " with value: " + keyAttributeValue.getS());
                }
            }

            @Override
            protected void onNullNonKeyAttribute(String attributeName) {
                if (attributeName.equalsIgnoreCase("SecondValue")) {
                    expectedFound.put(foundNullAttributeKey, true);
                } else {
                    fail("Incorrect NullNonKeyAttribute called, received " + attributeName);
                }
            }

            @Override
            protected void executeLowLevelRequest() {
                assertTrue(expectedFound.get(foundKeyKey));
                assertTrue(expectedFound.get(foundNullAttributeKey));
                assertTrue(getAttributeValueUpdates().get("firstValue").getValue().getS()
                        .equalsIgnoreCase("Value1"));
                assertTrue(getAttributeValueUpdates().get("version").getValue().getN()
                        .equalsIgnoreCase("1"));
            }

        };

        testHandler.execute();

    }

    @Test
    public void testSaveObjectHandlerWithAutogeneratedKey() {

        HashKeyAutoGenerated mockClass = new HashKeyAutoGenerated();
        mockClass.setRangeKey("range");
        mockClass.setOtherAttribute("other");
        String tableName = mapper.getTableName(HashKeyAutoGenerated.class, mockClass,
                config);

        final Map<String, Boolean> expectedFound = new HashMap<String, Boolean>();
        final String foundRangeKey = "RangeKey";

        SaveObjectHandler testHandler = mapper.new SaveObjectHandler(
                HashKeyAutoGenerated.class, mockClass,
                tableName, config, mapper.getConverter(config), null) {

            @Override
            protected void onKeyAttributeValue(String attributeName,
                    AttributeValue keyAttributeValue) {
                if (attributeName.equalsIgnoreCase("rangeKey")
                        && keyAttributeValue.getS().equalsIgnoreCase("range")) {
                    expectedFound.put(foundRangeKey, true);
                } else {
                    fail("Incorrect onKeyAttributeValueCalled --- received attributeName: "
                            + attributeName + " with value: " + keyAttributeValue.getS());
                }
            }

            @Override
            protected void onNullNonKeyAttribute(String attributeName) {
                fail("No null attributes should have been found");
            }

            @Override
            protected void executeLowLevelRequest() {
                assertNotNull(getAttributeValueUpdates().get("key").getValue().getS());
                assertTrue(expectedFound.get(foundRangeKey));
                assertTrue(getAttributeValueUpdates().get("otherAttribute").getValue().getS()
                        .equalsIgnoreCase("other"));
            }

        };

        testHandler.execute();

    }

    // ----Mock test classes -----

    @DynamoDBTable(tableName = "aws-android-sdk-dynamodbmapper-test")
    private static final class MockTwoValuePlusVersionClass {
        private String id;
        private String firstValue;
        private String secondValue;
        private Integer version;

        public MockTwoValuePlusVersionClass() {
        }

        public MockTwoValuePlusVersionClass(String id, String firstValue, String secondValue) {
            this.id = id;
            this.firstValue = firstValue;
            this.secondValue = secondValue;
        }

        @DynamoDBHashKey
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        @DynamoDBVersionAttribute
        public Integer getVersion() {
            return version;
        }

        public void setVersion(Integer version) {
            this.version = version;
        }

        @DynamoDBAttribute
        public String getFirstValue() {
            return firstValue;
        }

        public void setFirstValue(String value) {
            this.firstValue = value;
        }

        public String getSecondValue() {
            return secondValue;
        }

        public void setSecondValue(String secondValue) {
            this.secondValue = secondValue;
        }
    }

    @DynamoDBTable(tableName = "aws-android-sdk-dynamodbmapper-test-different-table")
    private static final class MockDifferentTableName {

        private String id;
        private String firstValue;

        public MockDifferentTableName(String id, String firstValue) {
            this.id = id;
            this.firstValue = firstValue;
        }

        public MockDifferentTableName() {

        }

        @DynamoDBHashKey
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        @DynamoDBAttribute
        public String getFirstValue() {
            return firstValue;
        }

        public void setFirstValue(String firstValue) {
            this.firstValue = firstValue;
        }
    }

    private static final class FixedCapture<T> extends Capture<T> {

        public static interface CapCallback<T> {
            public void valueSet(T value);
        }

        CapCallback<T> callback;

        public FixedCapture(CaptureType all, CapCallback callback) {
            super(CaptureType.ALL);
            this.callback = callback;
        }

        @Override
        public void setValue(T value) {
            callback.valueSet(value);
            if (!hasCaptured()) {
                super.setValue(value);
            }
        }
    }

}