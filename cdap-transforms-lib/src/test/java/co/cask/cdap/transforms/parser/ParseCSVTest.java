package co.cask.cdap.transforms.parser;

import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.etl.api.Transform;
import co.cask.cdap.transforms.MockEmitter;

import org.junit.Assert;
import org.junit.Test;


public class ParseCSVTest {

  private static final Schema INPUT1 = Schema.recordOf("input1",
                                                       Schema.Field.of("body", Schema.of(Schema.Type.STRING)));
  
  private static final Schema OUTPUT1 = Schema.recordOf("output1",
                                                        Schema.Field.of("a", Schema.of(Schema.Type.STRING)),
                                                        Schema.Field.of("b", Schema.of(Schema.Type.STRING)),
                                                        Schema.Field.of("c", Schema.of(Schema.Type.STRING)),
                                                        Schema.Field.of("d", Schema.of(Schema.Type.STRING)),
                                                        Schema.Field.of("e", Schema.of(Schema.Type.STRING)));

  private static final Schema OUTPUT2 = Schema.recordOf("output2",
                                                        Schema.Field.of("a", Schema.of(Schema.Type.LONG)),
                                                        Schema.Field.of("b", Schema.of(Schema.Type.STRING)),
                                                        Schema.Field.of("c", Schema.of(Schema.Type.INT)),
                                                        Schema.Field.of("d", Schema.of(Schema.Type.DOUBLE)),
                                                        Schema.Field.of("e", Schema.of(Schema.Type.BOOLEAN)));

  @Test
  public void testDefaultCSVParser() throws Exception {
    String s = OUTPUT1.toString();
    ParseCSV.Config config = new ParseCSV.Config("DEFAULT", "body", OUTPUT1.toString());
    Transform<StructuredRecord, StructuredRecord> transform = new ParseCSV(config);
    transform.initialize(null);

    MockEmitter<StructuredRecord> emitter = new MockEmitter<>();
    
    // Test missing field. 
    emitter.clear();
    transform.transform(StructuredRecord.builder(INPUT1)
                          .set("body", "1,2,3,4,").build(), emitter);
    Assert.assertEquals("1", emitter.getEmitted().get(0).get("a"));
    Assert.assertEquals("2", emitter.getEmitted().get(0).get("b"));
    Assert.assertEquals("3", emitter.getEmitted().get(0).get("c"));
    Assert.assertEquals("4", emitter.getEmitted().get(0).get("d"));
    Assert.assertEquals("", emitter.getEmitted().get(0).get("e"));

    // Test adding quote to field value. 
    emitter.clear();
    transform.transform(StructuredRecord.builder(INPUT1)
                          .set("body", "1,2,3,'4',5").build(), emitter);
    Assert.assertEquals("1", emitter.getEmitted().get(0).get("a"));
    Assert.assertEquals("2", emitter.getEmitted().get(0).get("b"));
    Assert.assertEquals("3", emitter.getEmitted().get(0).get("c"));
    Assert.assertEquals("'4'", emitter.getEmitted().get(0).get("d"));
    Assert.assertEquals("5", emitter.getEmitted().get(0).get("e"));

    // Test adding spaces in a field and quoted field value. 
    emitter.clear();
    transform.transform(StructuredRecord.builder(INPUT1)
                          .set("body", "1,2, 3 ,'4',5").build(), emitter);
    Assert.assertEquals("1", emitter.getEmitted().get(0).get("a"));
    Assert.assertEquals("2", emitter.getEmitted().get(0).get("b"));
    Assert.assertEquals(" 3 ", emitter.getEmitted().get(0).get("c"));
    Assert.assertEquals("'4'", emitter.getEmitted().get(0).get("d"));
    Assert.assertEquals("5", emitter.getEmitted().get(0).get("e"));
    
    // Test Skipping empty lines.
    emitter.clear();
    transform.transform(StructuredRecord.builder(INPUT1)
                          .set("body", "1,2,3,4,5\n\n").build(), emitter);
    Assert.assertEquals("1", emitter.getEmitted().get(0).get("a"));
    Assert.assertEquals("2", emitter.getEmitted().get(0).get("b"));
    Assert.assertEquals("3", emitter.getEmitted().get(0).get("c"));
    Assert.assertEquals("4", emitter.getEmitted().get(0).get("d"));
    Assert.assertEquals("5", emitter.getEmitted().get(0).get("e"));
    Assert.assertEquals(1, emitter.getEmitted().size());
    
    // Test multiple records
    emitter.clear();
    transform.transform(StructuredRecord.builder(INPUT1)
                          .set("body", "1,2,3,4,5\n6,7,8,9,10").build(), emitter);
    Assert.assertEquals("1", emitter.getEmitted().get(0).get("a"));
    Assert.assertEquals("2", emitter.getEmitted().get(0).get("b"));
    Assert.assertEquals("3", emitter.getEmitted().get(0).get("c"));
    Assert.assertEquals("4", emitter.getEmitted().get(0).get("d"));
    Assert.assertEquals("5", emitter.getEmitted().get(0).get("e"));
    Assert.assertEquals("6", emitter.getEmitted().get(1).get("a"));
    Assert.assertEquals("7", emitter.getEmitted().get(1).get("b"));
    Assert.assertEquals("8", emitter.getEmitted().get(1).get("c"));
    Assert.assertEquals("9", emitter.getEmitted().get(1).get("d"));
    Assert.assertEquals("10", emitter.getEmitted().get(1).get("e"));
    
    // Test with records supporting different types. 
    emitter.clear();
    ParseCSV.Config config1 = new ParseCSV.Config("DEFAULT", "body", OUTPUT2.toString());
    Transform<StructuredRecord, StructuredRecord> transform1 = new ParseCSV(config1);
    transform1.initialize(null);
    
    transform1.transform(StructuredRecord.builder(INPUT1)
                          .set("body", "10,stringA,3,4.32,true").build(), emitter);
    Assert.assertEquals(10L, emitter.getEmitted().get(0).get("a"));
    Assert.assertEquals("stringA", emitter.getEmitted().get(0).get("b"));
    Assert.assertEquals(3, emitter.getEmitted().get(0).get("c"));
    Assert.assertEquals(4.32, emitter.getEmitted().get(0).get("d"));
    Assert.assertEquals(true, emitter.getEmitted().get(0).get("e"));
  }
  
  @Test(expected=RuntimeException.class)
  public void testDoubleException() throws Exception {
    MockEmitter<StructuredRecord> emitter = new MockEmitter<>();
    ParseCSV.Config config = new ParseCSV.Config("DEFAULT", "body", OUTPUT2.toString());
    Transform<StructuredRecord, StructuredRecord> transform = new ParseCSV(config);
    transform.initialize(null);
    transform.transform(StructuredRecord.builder(INPUT1)
                          .set("body", "10,stringA,3,,true").build(), emitter);
  }

  @Test(expected=RuntimeException.class)
  public void testIntException() throws Exception {
    MockEmitter<StructuredRecord> emitter = new MockEmitter<>();
    ParseCSV.Config config = new ParseCSV.Config("DEFAULT", "body", OUTPUT2.toString());
    Transform<StructuredRecord, StructuredRecord> transform = new ParseCSV(config);
    transform.initialize(null);
    transform.transform(StructuredRecord.builder(INPUT1)
                          .set("body", "10,stringA,,4.32,true").build(), emitter);
  }

  @Test(expected=RuntimeException.class)
  public void testLongException() throws Exception {
    MockEmitter<StructuredRecord> emitter = new MockEmitter<>();
    ParseCSV.Config config = new ParseCSV.Config("DEFAULT", "body", OUTPUT2.toString());
    Transform<StructuredRecord, StructuredRecord> transform = new ParseCSV(config);
    transform.initialize(null);
    transform.transform(StructuredRecord.builder(INPUT1)
                          .set("body", ",stringA,3,4.32,true").build(), emitter);
  }

}