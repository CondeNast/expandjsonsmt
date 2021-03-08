package com.redhat.insights.expandjsonsmt;

import java.util.Iterator;
import java.util.Map.Entry;

import org.apache.kafka.connect.errors.ConnectException;
import org.bson.*;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka Connect parsing schema methods.
 */
class SchemaParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaParser.class);

    /**
     * Get Struct schema according to input document.
     * @param doc Parsed document or null.
     */
    static Schema bsonDocument2Schema(BsonDocument doc) {
        return bsonDocument2SchemaBuilder(doc).build();
    }

    private static SchemaBuilder bsonDocument2SchemaBuilder(BsonDocument doc) {
        final SchemaBuilder schemaBuilder = SchemaBuilder.struct().optional();
        if (doc != null) {
            for(Entry<String, BsonValue> entry : doc.entrySet()) {
                final BsonValue bsonValue = entry.getValue();
                boolean lastPass = false;
                if (bsonValue.isArray()) {
                    if (bsonValue.asArray().size() <= 1) {
                        lastPass = true;
                    }
                }
                addFieldSchema(entry, schemaBuilder, lastPass);
            }
        }

        return schemaBuilder;
    }

    private static SchemaBuilder bsonArray2SchemaBuilder(BsonDocument doc, boolean lastPass) {
        final SchemaBuilder schemaBuilder = SchemaBuilder.struct().optional();
        for(Entry<String, BsonValue> entry : doc.entrySet()) {
            addFieldSchema(entry, schemaBuilder, lastPass);
        }

        return schemaBuilder;
    }


    private static void addFieldSchema(Entry<String, BsonValue> keyValuesforSchema, SchemaBuilder builder, boolean lastPass) {
        try {
            final String key = keyValuesforSchema.getKey();
            final BsonValue bsonValue = keyValuesforSchema.getValue();
            final Schema schema = bsonValue2Schema(bsonValue, lastPass);
            if (schema != null) {
                builder.field(key, schema);
            }
        } catch (Exception e) {
            LOGGER.warn("Couldn't process json field: " + keyValuesforSchema.toString(), e);
        }
    }

    private static Schema bsonValue2Schema(BsonValue bsonValue, boolean lastPass) {
        switch (bsonValue.getBsonType()) {
        case NULL:
        case STRING:
        case JAVASCRIPT:
        case OBJECT_ID:
        case DECIMAL128:
            return Schema.OPTIONAL_STRING_SCHEMA;

        case DOUBLE:
            return Schema.OPTIONAL_FLOAT64_SCHEMA;

        case BINARY:
            return Schema.OPTIONAL_BYTES_SCHEMA;

        case INT32:
        case TIMESTAMP:
            return Schema.OPTIONAL_INT32_SCHEMA;

        case INT64:
        case DATE_TIME:
            return Schema.OPTIONAL_INT64_SCHEMA;

        case BOOLEAN:
            return Schema.OPTIONAL_BOOLEAN_SCHEMA;

        case DOCUMENT:
            return bsonDocument2Schema(bsonValue.asDocument());

        case ARRAY:
            Schema arraySchema = getArrayMemberSchema(bsonValue.asArray(), lastPass);
            if (arraySchema == null) {
                return null;
            }
            return SchemaBuilder.array(arraySchema).optional().build();

        default:
            return null;
        }
    }

    /**
     * Get first not-null member value of the array.
     * Check the same schema of all array members.
     */
    private static BsonValue getArrayElement(BsonArray bsonArray) {
        BsonValue bsonValue = new BsonNull();
        // Get first not-null element type
        for (BsonValue element : bsonArray.asArray()) {
           if (element.getBsonType() != BsonType.NULL) {
               bsonValue = element;
               break;
           }
        }

        // validate all members type
        for (BsonValue element: bsonArray.asArray()) {
            if (element.getBsonType() != bsonValue.getBsonType() && element.getBsonType() != BsonType.NULL) {
                throw new ConnectException(String.format("Field is not a homogenous array (%s x %s).",
                        bsonValue.toString(), element.getBsonType().toString()));
            }
        }
        return bsonValue;
    }

    private static Schema getArrayMemberSchema(BsonArray bsonArr, boolean lastPass) {
        if (lastPass) {
            if (bsonArr.isEmpty()) {
                return Schema.OPTIONAL_STRING_SCHEMA;
            }
        }
        if (bsonArr.isEmpty()) {
            return null;
            // return Schema.OPTIONAL_STRING_SCHEMA;
        }

        final BsonValue elementSample = getArrayElement(bsonArr);
        if (elementSample.isDocument()) {
            return buildDocumentUnionSchema(bsonArr);
        }

        final Schema schema = bsonValue2Schema(elementSample, lastPass);
        if (schema == null) {
            throw new ConnectException("Array has unrecognized member schema.");
        }

        return schema;
    }

    /*
     * if the array contains a heterogeneous set of documents create a member schema that's an union
     * of the document types
     */
    private static Schema buildDocumentUnionSchema(BsonArray array) {
        boolean lastPass = false;
        SchemaBuilder builder = null;

        Iterator<BsonValue> iterator = array.iterator();
        while (iterator.hasNext()) {
            BsonValue element = iterator.next();
            if (!iterator.hasNext()) {
                lastPass = true;
            }
            if (!element.isDocument()) {
                continue;
            }

            if (builder == null) {
                builder = bsonArray2SchemaBuilder(element.asDocument(), lastPass);
                continue;
            }

            for(Entry<String, BsonValue> entry : element.asDocument().entrySet()) {
                // Field builderField = builder.field(entry.getKey());
                // if (builderField == null || builderField.schema().valueSchema().equals(Schema.OPTIONAL_STRING_SCHEMA)) {
                if (builder.field(entry.getKey()) == null) {

                    addFieldSchema(entry, builder, lastPass);
                }
            }
        }

        return builder.build();
    }
}
