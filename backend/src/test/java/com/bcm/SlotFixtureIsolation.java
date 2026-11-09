package com.bcm;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.UUID;
/** Temporarily retire shared templates while a test creates its own active catalog.
 * Runs outside the test transaction; restores only the rows it retired, even on failure.
 * Exclusion constraints remain enabled for every fixture insert/update. */
public class SlotFixtureIsolation extends AbstractTestExecutionListener {
 private static final String KEY=SlotFixtureIsolation.class.getName();
 @Override public int getOrder(){return 1000;}
 @Override public void beforeTestMethod(TestContext context){
  var jdbc=context.getApplicationContext().getBean(JdbcTemplate.class);
  var ids=jdbc.queryForList("select id from time_slots where deleted_at is null",UUID.class);
  context.setAttribute(KEY,ids);
  for(var id:ids) jdbc.update("update time_slots set deleted_at=current_timestamp where id=?",id);
 }
 @Override @SuppressWarnings("unchecked") public void afterTestMethod(TestContext context){
  var ids=(List<UUID>)context.removeAttribute(KEY);
  if(ids==null)return;
  var jdbc=context.getApplicationContext().getBean(JdbcTemplate.class);
  for(var id:ids)jdbc.update("update time_slots set deleted_at=null where id=?",id);
 }
}
