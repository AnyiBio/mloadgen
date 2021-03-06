/*
 *  This Source Code Form is subject to the terms of the Mozilla Public
 *  * License, v. 2.0. If a copy of the MPL was not distributed with this
 *  * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package net.coru.mloadgen.extractor.parser.jschema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import net.coru.mloadgen.exception.MLoadGenException;
import net.coru.mloadgen.extractor.parser.SchemaParser;
import net.coru.mloadgen.model.json.ArrayField;
import net.coru.mloadgen.model.json.BooleanField;
import net.coru.mloadgen.model.json.DateField;
import net.coru.mloadgen.model.json.EnumField;
import net.coru.mloadgen.model.json.Field;
import net.coru.mloadgen.model.json.IntegerField;
import net.coru.mloadgen.model.json.NumberField;
import net.coru.mloadgen.model.json.ObjectField;
import net.coru.mloadgen.model.json.Schema;
import net.coru.mloadgen.model.json.StringField;
import net.coru.mloadgen.model.json.UUIDField;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.IterableUtils;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;

public class JSONSchemaParser implements SchemaParser {

  private static final Set<String> cyclingSet = new HashSet<>();

  public static final String REQUIRED = "required";

  public static final String TYPE = "type";

  public static final String PROPERTIES = "properties";

  public static final String ANY_OF = "anyOf";

  public static final String ALL_OF = "allOf";

  public static final String ONE_OF = "oneOf";

  public static final String $_REF = "$ref";

  private final ObjectMapper mapper = new ObjectMapper();

  private final Map<String, Field> definitionsMap = new HashMap<>();

  @Override
  public Schema parse(String jsonSchema) {
    definitionsMap.clear();
    List<Field> fields = new ArrayList<>();
    Schema schema;
    try {
      JsonNode jsonNode = mapper.readTree(jsonSchema);

      JsonNode definitions = jsonNode.path("definitions");
      processDefinitions(definitions);

      JsonNode schemaId = jsonNode.path("$id");
      JsonNode schemaName = jsonNode.path("$schema");
      JsonNode requiredList = jsonNode.path(REQUIRED);
      JsonNode type = jsonNode.path(TYPE);

      CollectionUtils.collect(jsonNode.path(PROPERTIES).fieldNames(),
          fieldName -> buildProperty(fieldName, jsonNode.path(PROPERTIES).get(fieldName)),
          fields);
      schema = Schema.builder()
              .id(schemaId.asText())
              .name(schemaName.asText())
              .requiredFields(requiredList.asText().split(","))
              .type(type.asText())
              .properties(fields)
              .descriptions(definitionsMap.values())
              .build();
    } catch (IOException e) {
      throw new MLoadGenException("Wrong Json Schema", e);
    }

    return schema;
  }

  private void processDefinitions(JsonNode definitions) {
    for (Iterator<Entry<String, JsonNode>> it = definitions.fields(); it.hasNext(); ) {
      Entry<String, JsonNode> definitionNode = it.next();
      if (!isRefNode(definitionNode.getValue())) {
        definitionsMap.putIfAbsent(definitionNode.getKey(), buildDefinition(definitionNode.getKey(), definitionNode.getValue(), definitions));
      } else if (isRefNodeSupported(definitionNode.getValue())) {
        String referenceName = extractRefName(definitionNode.getValue());
        if (definitionsMap.containsKey(referenceName)) {
          definitionsMap.put(definitionNode.getKey(), buildDefinition(definitionNode.getKey(), definitionNode.getValue(), definitions));
        } else {
          if (!isRefNode(definitions.path(referenceName))) {
            if (cyclingSet.add(referenceName)) {
              definitionsMap.put(definitionNode.getKey(), buildDefinition(definitionNode.getKey(), definitions.path(referenceName), definitions));
              cyclingSet.remove(referenceName);
            } else {
              throw new MLoadGenException("Wrong Json Schema, Missing definition");
            }
          } else {
            throw new MLoadGenException("Wrong Json Schema, Missing definition");
          }
        }
      }
    }
  }

  private Field buildDefinition(String fieldName, JsonNode jsonNode, JsonNode definitions) {
    Field result;
    if (isAnyType(jsonNode)) {
      result = buildField(fieldName, jsonNode);
    } else if (isRefNode(jsonNode)) {
      String referenceName = extractRefName(jsonNode);
      if (definitionsMap.containsKey(referenceName)) {
        result = definitionsMap.get(referenceName);
      } else {
        if (cyclingSet.add(referenceName)) {
          result = extractDefinition(referenceName, definitions);
          cyclingSet.remove(referenceName);
        } else {
          result = null;
        }
      }
    } else if (isCombine(jsonNode)) {
      if (Objects.nonNull(jsonNode.get(ANY_OF))) {
        result = chooseAnyOfDefinition(fieldName, jsonNode, ANY_OF, definitions);
      } else if (Objects.nonNull(jsonNode.get(ALL_OF))) {
        result = chooseAnyOfDefinition(fieldName, jsonNode, ALL_OF, definitions);
      } else {
        result = chooseAnyOfDefinition(fieldName, jsonNode, ONE_OF, definitions);
      }
    } else {
      result = buildDefinitionObjectField(fieldName, jsonNode, definitions);
    }
    return result;
  }

  private boolean isCombine(JsonNode jsonNode) {
    return Objects.nonNull(jsonNode.get(ANY_OF)) ||
            Objects.nonNull(jsonNode.get(ALL_OF)) ||
            Objects.nonNull(jsonNode.get(ONE_OF));
  }

  private String getSafeType(JsonNode jsonNode) {
    String nodeType;
    if (jsonNode.findPath(TYPE).isArray()) {
      nodeType = getNonNUll(jsonNode.findPath(TYPE).elements());
    } else {
      nodeType = jsonNode.findPath(TYPE).textValue().toLowerCase();
    }
    return nodeType;
  }

  private String getNonNUll(Iterator<JsonNode> typeIt) {
    String type = null;
    while (typeIt.hasNext() && Objects.isNull(type)) {
      type = typeIt.next().asText();
      if ("null".equalsIgnoreCase(type)) {
        type = null;
      }
    }
    return type;
  }

  private Field extractDefinition(String referenceName, JsonNode definitions) {
    JsonNode field = definitions.path(referenceName);
    if (Objects.nonNull(field)) {
      Field definition = buildDefinition(referenceName, field, definitions);
      definitionsMap.put(referenceName, definition);
      return definition;
    }
    return null;
  }

  private Field chooseAnyOfDefinition(String fieldName, JsonNode jsonNode, String type, JsonNode definitions) {
    List<JsonNode> options = IteratorUtils.toList(jsonNode.get(type).elements());
    int optionsNumber = options.size();
    Field resultObject;
    switch (type) {
      case ANY_OF:
      case ONE_OF:
        resultObject = buildDefinition(fieldName, jsonNode.path(type).get(RandomUtils.nextInt(0, optionsNumber)), definitions);
        break;
      default:
        resultObject = buildDefinition(fieldName, jsonNode.path(type), definitions);
        break;
    }
    return resultObject;
  }

  private Field buildDefinitionObjectField(String fieldName, JsonNode jsonNode, JsonNode definitions) {
    List<Field> properties = new ArrayList<>();
    if (Objects.nonNull(jsonNode.get(PROPERTIES))) {
      CollectionUtils.collect(jsonNode.path(PROPERTIES).fields(),
                              field -> buildDefinition(field.getKey(), field.getValue(), definitions), properties);
      List<String> strRequired = jsonNode.findValuesAsText(REQUIRED);
      CollectionUtils.filter(strRequired, StringUtils::isNotEmpty);
      return ObjectField.builder().name(fieldName).properties(properties).required(strRequired).build();
    } else if (Objects.nonNull(jsonNode.get($_REF))) {
      String referenceName = extractRefName(jsonNode);
      if (definitionsMap.containsKey(referenceName)) {
        return definitionsMap.get(referenceName).cloneField(fieldName);
      } else if (cyclingSet.add(referenceName)){
        return extractDefinition(referenceName, definitions);
      } else {
        return null;
      }
    } else {
      List<Field> fieldList = new ArrayList<>();
      jsonNode.fields()
              .forEachRemaining(property -> fieldList.add(buildProperty(property.getKey(), property.getValue())));
      return ObjectField.builder().name(fieldName).properties(fieldList).build();
    }
  }

  private boolean isRefNodeSupported(JsonNode jsonNode) {
    String reference = jsonNode.get($_REF).asText();
    return !reference.isEmpty() && reference.startsWith("#");
  }

  private boolean isRefNode(JsonNode jsonNode) {
    return Objects.nonNull(jsonNode.get($_REF));
  }

  private String extractRefName(JsonNode jsonNode) {
    String reference = jsonNode.get($_REF).asText();
    return extractRefName(reference);
  }

  private String extractRefName(String jsonNodeName) {
    return jsonNodeName.substring(jsonNodeName.lastIndexOf("/") + 1);
  }

  private Field buildProperty(String fieldName, JsonNode jsonNode) {
    Field result;
    if (isRefNode(jsonNode)) {
      if (isRefNodeSupported(jsonNode)) {
        String referenceName = extractRefName(jsonNode);
        if ("array".equalsIgnoreCase(jsonNode.findPath(TYPE).textValue())) {
          result = buildArrayField(fieldName, jsonNode, definitionsMap.get(referenceName).cloneField(null));
        } else {
          result = definitionsMap.get(referenceName).cloneField(fieldName);
        }
      } else {
        throw new MLoadGenException(String.format("Reference not Supported: %s", extractRefName(jsonNode)));
      }
    } else if (isAnyType(jsonNode)) {
      result = buildField(fieldName, jsonNode);
    } else if (isCombine(jsonNode)) {
      if (Objects.nonNull(jsonNode.get(ANY_OF))) {
        result = chooseAnyOf(fieldName, jsonNode, ANY_OF);
      } else if (Objects.nonNull(jsonNode.get(ALL_OF))) {
        result = chooseAnyOf(fieldName, jsonNode, ALL_OF);
      } else {
        result = chooseAnyOf(fieldName, jsonNode, ONE_OF);
      }
    } else if (hasProperties(jsonNode)){
      result = buildObjectField(fieldName, jsonNode);
    } else {
      throw new MLoadGenException("Not supported file");
    }
    return result;
  }

  private Field buildField(String fieldName, JsonNode jsonNode) {
    Field result;
    String nodeType = getSafeType(jsonNode).toLowerCase();
    switch (nodeType) {
      case "integer":
        result = IntegerField.builder().name(fieldName).build();
        break;
      case "number":
        result = buildNumberField(fieldName, jsonNode);
        break;
      case "array":
        result = buildArrayField(fieldName, jsonNode);
        break;
      case "object":
        result = buildObjectField(fieldName, jsonNode);
        break;
      case "boolean":
        result = buildBooleanField(fieldName);
        break;
      default:
        result = buildStringField(fieldName, jsonNode);
        break;
    }
    return result;
  }

  private boolean hasProperties(JsonNode jsonNode) {
    return Objects.nonNull(jsonNode.get(PROPERTIES));
  }

  private Field buildStringField(String fieldName, JsonNode jsonNode) {
    Field result;
    if (Objects.isNull(jsonNode.get("enum"))) {
      String regexStr = getSafeText(jsonNode, "pattern");
      int minLength = getSafeInt(jsonNode, "minLength");
      int maxLength = getSafeInt(jsonNode, "maxLength");
      String format = getSafeText(jsonNode, "format");
      if (Objects.nonNull(format)) {
        if (Set.of("date-time", "time", "date").contains(format)) {
          result = DateField.builder().name(fieldName).format(format).build();
        } else if ("uuid".equals(format)) {
          result = UUIDField.builder().name(fieldName).build();
        } else {
          result = StringField.builder().name(fieldName).format(format).build();
        }
      } else {
        result = StringField.builder().name(fieldName).regex(regexStr).minLength(minLength).maxlength(maxLength).format(format).build();
      }
    } else {
      result = buildEnumField(fieldName, jsonNode);
    }
    return result;
  }

  private int getSafeInt(JsonNode node, String field) {
    int result = 0;
    if (Objects.nonNull(node.get(field))) {
      result = node.get(field).asInt();
    }
    return result;
  }

  private String getSafeText(JsonNode node, String field) {
    String result = null;
    if (Objects.nonNull(node.get(field))) {
      result = node.get(field).asText();
    }
    return result;
  }

  private Field buildEnumField(String fieldName, JsonNode jsonNode) {
    List<String> valueList = new ArrayList<>();
    if (jsonNode.get("enum").isArray()) {
      valueList = extractValues(jsonNode.get("enum").elements());
    }
    return EnumField.builder().name(fieldName).defaultValue(valueList.get(0)).enumValues(valueList).build();
  }

  private List<String> extractValues(Iterator<JsonNode> enumValueList) {
    List<String> valueList = new ArrayList<>();
    while (enumValueList.hasNext()) {
      valueList.add(enumValueList.next().asText());
    }
    return valueList;
  }

  private boolean isAnyType(JsonNode node) {
    return Objects.nonNull(node.get(TYPE));
  }

  private Field chooseAnyOf(String fieldName, JsonNode jsonNode, String type) {
    List<JsonNode> properties = IteratorUtils.toList(jsonNode.get(type).elements());
    int optionsNumber = properties.size();
    Field resultObject;
    if (IterableUtils.matchesAll(properties, property -> property.hasNonNull(PROPERTIES)
            || property.hasNonNull("$ref"))) {
      switch (type) {
        case ANY_OF:
        case ONE_OF:
          resultObject = buildCombinedField(fieldName, Collections.singletonList(properties.get(RandomUtils.nextInt(0, optionsNumber))));
          break;
        default:
          resultObject = buildCombinedField(fieldName, properties);
          break;
      }
    } else if (IterableUtils.matchesAll(properties, property -> !property.hasNonNull(PROPERTIES)
            && !property.hasNonNull("$ref"))) {
      switch (type) {
        case ANY_OF:
        case ONE_OF:
          resultObject = buildCombinedType(fieldName, properties.get(RandomUtils.nextInt(0, optionsNumber)));
          break;
        default:
          throw new MLoadGenException("Incorrect type in combination");
      }
    } else {
      throw new MLoadGenException("Incorrect combination, types and properties mixed");
    }
    return resultObject;
  }

  private Field buildCombinedType(String fieldName, JsonNode property) {
    return buildField(fieldName, property);
  }

  private Field buildCombinedField(String fieldName, List<JsonNode> properties) {
    Field resultObject;
    List<Field> fields = new ArrayList<>();
    for (JsonNode property : properties) {
      if (isRefNode(property)) {
        String referenceName = extractRefName(property);
        Field refField = definitionsMap.get(referenceName).cloneField(fieldName);
        if (isAnyType(property)) {
          fields.add(refField);
        } else {
          fields.addAll(refField.getProperties());
        }
      } else {
        for (Iterator<Entry<String, JsonNode>> it = property.get(PROPERTIES).fields(); it.hasNext(); ) {
          Entry<String, JsonNode> innProperty = it.next();
          fields.add(buildProperty(innProperty.getKey(), innProperty.getValue()));
        }
      }
    }
    resultObject = buildObjectField(fieldName, fields);
    return resultObject;
  }

  private Field buildNumberField(String fieldName, JsonNode jsonNode) {
    String maximum = jsonNode.path("maximum").asText("0");
    String minimum = jsonNode.path("minimum").asText("0");
    String exclusiveMaximum = jsonNode.path("exclusiveMaximum").asText("0");
    String exclusiveMinimum = jsonNode.path("exclusiveMinimum").asText("0");
    String multipleOf = jsonNode.path("multipleOf").asText("0");

    return NumberField
        .builder()
        .name(fieldName)
        .maximum(safeGetNumber(maximum))
        .minimum(safeGetNumber(minimum))
        .exclusiveMaximum(safeGetNumber(exclusiveMaximum))
        .exclusiveMinimum(safeGetNumber(exclusiveMinimum))
        .multipleOf(safeGetNumber(multipleOf))
        .build();
  }

  private Number safeGetNumber(String numberStr) {
    Number number;
    if (numberStr.contains(".")) {
      number = Float.parseFloat(numberStr);
    } else {
      number = Long.parseLong(numberStr);
    }
    return number;
  }

  private Field buildArrayField(String fieldName, JsonNode jsonNode) {
    return buildArrayField(fieldName, jsonNode, buildProperty(null, jsonNode.path("items")));
  }

  private Field buildArrayField(String fieldName, JsonNode jsonNode, Field value) {
    String minItems = jsonNode.path("minItems").asText("0");
    String uniqueItems = jsonNode.path("uniqueItems").asText("false");
    return ArrayField
        .builder()
        .name(fieldName)
        .value(value)
        .minItems(Integer.parseInt(minItems))
        .uniqueItems(Boolean.parseBoolean(uniqueItems))
        .build();
  }

  private Field buildObjectField(String fieldName, JsonNode jsonNode) {
    List<Field> properties = new ArrayList<>();
    List<String> strRequired = jsonNode.findValuesAsText(REQUIRED);
    CollectionUtils.filter(strRequired, StringUtils::isNotEmpty);
    if (!isCombine(jsonNode)) {
      CollectionUtils.collect(jsonNode.path(PROPERTIES).fields(), field -> buildProperty(field.getKey(), field.getValue()), properties);
      return ObjectField.builder().name(fieldName).properties(properties).required(strRequired).build();
    } else {
      Field result;
      if (Objects.nonNull(jsonNode.get(ANY_OF))) {
        result = chooseAnyOf(fieldName, jsonNode, ANY_OF);
      } else if (Objects.nonNull(jsonNode.get(ALL_OF))) {
        result = chooseAnyOf(fieldName, jsonNode, ALL_OF);
      } else {
        result = chooseAnyOf(fieldName, jsonNode, ONE_OF);
      }
      return result;
    }
  }

  private Field buildObjectField(String fieldName, List<Field> properties) {
    return ObjectField.builder().name(fieldName).properties(properties).build();
  }

  private Field buildBooleanField(String fieldName) {
    return BooleanField.builder().name(fieldName).build();
  }
}
