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
import co.cask.cdap.api.data.schema.Schema.Field;
import co.cask.cdap.api.plugin.PluginConfig;
import co.cask.cdap.etl.api.Emitter;
import co.cask.cdap.etl.api.PipelineConfigurer;
import co.cask.cdap.etl.api.Transform;
import co.cask.cdap.etl.api.TransformContext;
import com.google.common.collect.Maps;
import org.apache.commons.codec.binary.Base32;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Encodes the input fields as BASE64, BASE32 or HEX.
 */
@Plugin(type = "transform")
@Name("Encoder")
@Description("Encodes the input field(s) using Base64, Base32 or Hex")
public class Encoder extends Transform<StructuredRecord, StructuredRecord> {
  private static final Logger LOG = LoggerFactory.getLogger(Encoder.class);
  private final Config config;

  // Output Schema associated with transform output.
  private Schema outSchema;

  // Mapping of input field to encoder type. 
  private final Map<String, EncodeDecodeType> encodeMap = Maps.newTreeMap();
  
  // Encoder handlers.
  private final Base64 base64Encoder = new Base64();
  private final Base32 base32Encoder = new Base32();
  private final Hex hexEncoder = new Hex();
  
  // Output Field name to type map
  private Map<String, Schema.Type> outSchemaMap = Maps.newHashMap();

  // This is used only for tests, otherwise this is being injected by the ingestion framework.
  public Encoder(Config config) {
    this.config = config;
  }
  
  private void parseConfiguration(String foo) throws IllegalArgumentException {
    String[] mappings = config.encode.split(",");
    for(String mapping : mappings) {
      String[] params = mapping.split(":");
      
      // If format is not right, then we throw an exception.
      if(params.length < 2) {
        throw new IllegalArgumentException("Configuration " + mapping + " is in-correctly formed. " +
                                             "Format should be <fieldname>:<encoder-type>");  
      }
      
      String field = params[0];
      String type = params[1].toUpperCase();
      EncodeDecodeType eType = EncodeDecodeType.BASE64;
      
      switch(type) {
        case "STRING_BASE64":
          eType = EncodeDecodeType.STRING_BASE64;
          break;
        
        case "STRING_BASE32":
          eType = EncodeDecodeType.STRING_BASE32;
          break;
        
        case "BASE64":
          eType = EncodeDecodeType.BASE64;
          break;
        
        case "BASE32":
          eType = EncodeDecodeType.BASE32;
          break;
        
        case "HEX":
          eType = EncodeDecodeType.HEX;
          break;
        
        case "NONE":
          eType = EncodeDecodeType.NONE;
          break;
        
        default:
          throw new IllegalArgumentException("Unknown encoder type " + type + " found in mapping " + mapping);
      }
      
      if(encodeMap.containsKey(field)) {
        throw new IllegalArgumentException("Field " + field + " already has encoder set. Check the mapping.");  
      } else {
        encodeMap.put(field, eType);
      }
    }
  }
  
  @Override
  public void initialize(TransformContext context) throws Exception {
    super.initialize(context);
    parseConfiguration(config.encode);
    try {
      outSchema = Schema.parseJson(config.schema);
      List<Field> outFields = outSchema.getFields();
      for(Field field : outFields) {
        outSchemaMap.put(field.getName(), field.getSchema().getType());  
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Format of schema specified is invalid. Please check the format.");
    }
  }

  @Override
  public void configurePipeline(PipelineConfigurer pipelineConfigurer) throws IllegalArgumentException {
    super.configurePipeline(pipelineConfigurer);
    parseConfiguration(config.encode);
    // Check if schema specified is a valid schema or no. 
    try {
      Schema.parseJson(config.schema);
    } catch (IOException e) {
      throw new IllegalArgumentException("Format of schema specified is invalid. Please check the format.");
    }
  }
  

  @Override
  public void transform(StructuredRecord in, Emitter<StructuredRecord> emitter) throws Exception {
    StructuredRecord.Builder builder = StructuredRecord.builder(outSchema);
    
    Schema inSchema = in.getSchema();
    List<Field> inFields = inSchema.getFields();
    
    // Iterate through input fields. Check if field name is present 
    // in the fields that need to be encoded, if it's not then write 
    // to output as it is. 
    for(Field field : inFields) {
      String name = field.getName();
      
      // Check if output schema also have the same field name. If it's not 
      // then throw an exception. 
      if(!outSchemaMap.containsKey(name)) {
        throw new Exception("Field " + name + " is not defined in the output.");  
      }
      
      Schema.Type outFieldType = outSchemaMap.get(name);
      
      // Check if the input field name is configured to be encoded. If the field is not 
      // present or is defined as none, then pass through the field as is. 
      if(!encodeMap.containsKey(name) || encodeMap.get(name) == EncodeDecodeType.NONE) {
        builder.set(name, in.get(name));          
      } else {
        // Now, the input field could be of type String or byte[], so transform everything
        // to byte[] 
        byte[] obj = new byte[0];
        if(field.getSchema().getType() == Schema.Type.STRING) {
          obj = ((String)in.get(name)).getBytes();  
        } else if (field.getSchema().getType() == Schema.Type.BYTES){
          obj = in.get(name);
        }
        
        // Now, based on the encode type configured for the field - encode the byte[] of the 
        // value.
        byte[] outValue = new byte[0];
        EncodeDecodeType type = encodeMap.get(name);
        if(type == EncodeDecodeType.STRING_BASE32) {
          outValue = base32Encoder.encodeAsString(obj).getBytes();
        } else if (type == EncodeDecodeType.BASE32) {
          outValue = base32Encoder.encode(obj);
        } else if (type == EncodeDecodeType.STRING_BASE64) {
          outValue = base64Encoder.encodeAsString(obj).getBytes();
        } else if (type == EncodeDecodeType.BASE64) {
          outValue = base64Encoder.encode(obj);
        } else if (type == EncodeDecodeType.HEX) {
          outValue = hexEncoder.encode(obj);
        }
        
        // Depending on the output field type, either convert it to 
        // Bytes or to String. 
        if(outFieldType == Schema.Type.BYTES) {
          builder.set(name, outValue);
        } else if (outFieldType == Schema.Type.STRING) {
          builder.set(name, new String(outValue));
        }
      }
    }
    emitter.emit(builder.build());
  }

  public static class Config extends PluginConfig {
    @Name("encode")
    @Description("Specify the field and encode type combination. " +
      "Format is <field>:<encode-type>[,<field>:<encode-type>]*")
    private final String encode;

    @Name("schema")
    @Description("Specifies the output schema")
    private final String schema;
    
    public Config(String encode, String schema) {
      this.encode = encode;
      this.schema = schema;
    }
  }
}

