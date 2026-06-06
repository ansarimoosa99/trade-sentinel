package com.example.tradesentinel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.tradesentinel.model.OrderEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvOrderParserTest {
  private final CsvOrderParser parser = new CsvOrderParser();

  @Test
  void parsesValidCsvInTimeOrder() {
    String csv = """
        order_id,trader_id,account_id,symbol,exchange,side,quantity,price,event_type,event_time
        O-2,T-1,A-1,infy,nse,BUY,100,10.0,EXECUTE,2026-06-06T10:00:02Z
        O-1,T-1,A-1,infy,nse,BUY,100,10.0,NEW,2026-06-06T10:00:01Z
        """;

    List<OrderEvent> events = parser.parse(csv);

    assertThat(events).hasSize(2);
    assertThat(events.getFirst().orderId()).isEqualTo("O-1");
    assertThat(events.getFirst().symbol()).isEqualTo("INFY");
  }

  @Test
  void rejectsMissingRequiredColumns() {
    String csv = """
        order_id,trader_id,symbol
        O-1,T-1,INFY
        """;

    assertThatThrownBy(() -> parser.parse(csv))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing CSV columns");
  }
}
