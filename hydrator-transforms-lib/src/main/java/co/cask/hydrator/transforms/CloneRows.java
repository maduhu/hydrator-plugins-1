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
import org.apache.commons.codec.digest.DigestUtils;

import java.util.List;
import javax.annotation.Nullable;

@Plugin(type = "transform")
@Name("CloneRows")
@Description("Creates copies (clones) of a row and outputs them directly after the original row to the next steps.")
public class CloneRows extends Transform<StructuredRecord, StructuredRecord> {
  private final Config config;

  public CloneRows(Config config) {
    this.config = config;
  }

  @Override
  public void configurePipeline(PipelineConfigurer pipelineConfigurer) throws IllegalArgumentException {
    super.configurePipeline(pipelineConfigurer);
    if(config.copies == 0 || config.copies > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Number of copies specified '" + config.copies + "' is incorrect. Specify " +
                                       "proper integer range");
    }
  }

  @Override
  public void transform(StructuredRecord in, Emitter<StructuredRecord> emitter) throws Exception {
    List<Schema.Field> fields = in.getSchema().getFields();
    for(int i = 0; i < config.copies; ++i) {
      StructuredRecord.Builder builder = StructuredRecord.builder(in.getSchema());
      for(Schema.Field field : fields) {
        String name = field.getName();
        builder.set(name, in.get(name));
      }
      emitter.emit(builder.build());
    }
  }

  public static class Config extends PluginConfig {
    @Name("copies")
    @Description("Specifies number of copies to be made of every row.")
    private final int copies;
    
    public Config(int copies) {
      this.copies = copies;
    }
    
  }
}
