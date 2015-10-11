/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.hydrator.transforms;


import co.cask.cdap.api.annotation.Description;
import co.cask.cdap.api.annotation.Name;
import co.cask.cdap.api.annotation.Plugin;
import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.plugin.PluginConfig;
import co.cask.cdap.etl.api.Emitter;
import co.cask.cdap.etl.api.PipelineConfigurer;
import co.cask.cdap.etl.api.Transform;
import co.cask.cdap.etl.api.TransformContext;
import co.cask.cdap.etl.common.StructuredRecordStringConverter;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

@Plugin(type = "transform")
@Name("StreamFormatter")
@Description("Formats the data from Structured Record to CDAP Stream format.")
public class StreamFormatter extends Transform<StructuredRecord, StructuredRecord> {
  private static final Logger LOG = LoggerFactory.getLogger(StreamFormatter.class);
  private final Config config;
  private Gson gson;
  private Schema outSchema;
  private String[] headerFields = null;
  private String[] bodyFields = null;
  private String headerFieldName;
  private String bodyFieldName;

  public StreamFormatter(Config config) {
    this.config = config;
  }
  
  @Override
  public void initialize(TransformContext context) throws Exception {
    super.initialize(context);
    gson = new Gson();
    try {
      outSchema = Schema.parseJson(config.schema);
    } catch (IOException e) {
      throw new IllegalArgumentException("Output Schema specified is not a valid JSON. Please check the Schema JSON");
    }
    
    headerFields = config.header.split(",");
    if(config.body != null) {
      bodyFields = config.body.split(",");
    }
    
    for(Schema.Field field : outSchema.getFields()) {
      if (field.getSchema().getType() == Schema.Type.STRING) {
        bodyFieldName = field.getName();
      } else if (field.getSchema().getType() == Schema.Type.MAP) {
        headerFieldName = field.getName();
      }
    }
    
  }

  @Override
  public void configurePipeline(PipelineConfigurer pipelineConfigurer) throws IllegalArgumentException {
    super.configurePipeline(pipelineConfigurer);

    if(!config.format.equalsIgnoreCase("CSV") && !config.format.equalsIgnoreCase("TSV") 
        && !config.format.equalsIgnoreCase("JSON") && !config.format.equalsIgnoreCase("PSV")) {
      throw new IllegalArgumentException("Invalid format '" + config.format + "', specified. Allowed values are " +
                                           "CSV, TSV, PSV or JSON.");
    }
    
    try {
      Schema out = Schema.parseJson(config.schema);
      List<Schema.Field> fields = out.getFields();
      
      if (fields.size() != 2) {
        throw new IllegalArgumentException("Output schema should have only two fields. One of type " +
                                             "String for Stream body and other of type Map<String, String> for " +
                                             "Stream header.");  
      }
      
      if(fields.get(0).getSchema().getType() != Schema.Type.MAP &&
         fields.get(0).getSchema().getType() != Schema.Type.STRING) {
        throw new IllegalArgumentException("Field '" + fields.get(0).getName() + "' is not of type String or " +
                                             "Map<String, String>.");
      }

      if(fields.get(1).getSchema().getType() != Schema.Type.MAP &&
        fields.get(1).getSchema().getType() != Schema.Type.STRING) {
        throw new IllegalArgumentException("Field '" + fields.get(1).getName() + "' is not of type String or " +
                                             "Map<String, String>.");
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Output Schema specified is not a valid JSON. Please check the schema JSON");
    }
  }

  @Override
  public void transform(StructuredRecord in, Emitter<StructuredRecord> emitter) throws Exception {
    
    // Construct the header map based on the header fields specified 
    // as the input.
    Map<String, String> headers = Maps.newHashMap();
    for(String field : headerFields) {
      Object o = in.get(field);
      if (o != null) {
        headers.put(field, o.toString());
      }
    }
    
    // Get the input schema and filter out all the fields that have 
    // been specified in the bodyField or if there is none, then 
    // transfer all the fields from input to the output that will 
    // be transformed into the body.
    Schema schema = in.getSchema();
    if (bodyFields != null) {
      List<Schema.Field> f = Lists.newArrayList();
      // Iterate through input and when you find a field that's
      // also in bodyField, then that is written to Structured
      // record. 
      for(Schema.Field field : in.getSchema().getFields()) {
        for(String bodyField : bodyFields) {
          if (field.getName().equalsIgnoreCase(bodyField)) {
            f.add(field);
          }
        }
      }
      schema = Schema.recordOf("out", f);
    }

    // Create a new structured record using the schema. 
    StructuredRecord.Builder oBuilder = StructuredRecord.builder(schema);
    for(Schema.Field field : schema.getFields()) {
      oBuilder.set(field.getName(), in.get(field.getName()));
    }
    StructuredRecord record = oBuilder.build();

    // Convert the structured record to the format specified in the configuration.
    String finalBody = "";
    if(config.format.equalsIgnoreCase("CSV")) {
      finalBody = StructuredRecordStringConverter.toDelimitedString(record, ",");
    } else if(config.format.equalsIgnoreCase("TSV")) {
      finalBody = StructuredRecordStringConverter.toDelimitedString(record, "\t");
    } else if(config.format.equalsIgnoreCase("PSV")) {
      finalBody = StructuredRecordStringConverter.toDelimitedString(record, "|");
    } else if(config.format.equalsIgnoreCase("JSON")) {
      finalBody = StructuredRecordStringConverter.toJsonString(record);
    }
    
    // Construct the final stream record to be sent to stream writer.
    StructuredRecord.Builder builder = StructuredRecord.builder(outSchema);
    builder.set(headerFieldName, headers);
    builder.set(bodyFieldName, finalBody);
    emitter.emit(builder.build());
  }

  public static class Config extends PluginConfig {
    
    @Name("body")
    @Description("Specify the fields to be set in the body")
    @Nullable
    private String body;
    
    @Name("header")
    @Description("Specify the fields to be set in the header")
    private String header;
    
    @Name("format")
    @Description("Format of the body to be written to stream. Defaults CSV")
    private String format;
    
    @Name("schema")
    @Description("Output schema")
    private String schema;


    public Config(String header, String body, String format, String schema) {
      this.header = header;
      this.body = body;
      this.format = format;
      this.schema = schema;
    }

  }
}


