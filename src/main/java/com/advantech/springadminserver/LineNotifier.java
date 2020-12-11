package com.advantech.springadminserver;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceStatusChangedEvent;
import de.codecentric.boot.admin.server.notify.AbstractEventNotifier;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class LineNotifier extends AbstractEventNotifier {

  @Autowired
  private LineProperties lineProperties;
  private static final String DEFAULT_MESSAGE = "Instance #{name} (#{id}) is #{status}";
  private final SpelExpressionParser parser = new SpelExpressionParser();
  private List<String> notifyStatuses = Arrays.asList("UP", "DOWN", "OFFLINE");

  public LineNotifier(InstanceRepository repository) {
    super(repository);
  }

  @Override
  protected Mono<Void> doNotify(InstanceEvent event, Instance instance) {
    return Mono.fromRunnable(() -> {
      if (event instanceof InstanceStatusChangedEvent) {
        log.info("Instance {} ({}) is {}", instance.getRegistration().getName(),
            event.getInstance(),
            ((InstanceStatusChangedEvent) event).getStatusInfo().getStatus());

        String msg = this.getMessage(event, instance);
        this.pushMessage(msg);

      } else {
        log.info("Instance {} ({}) {}", instance.getRegistration().getName(), event.getInstance(),
            event.getType());
      }
    });
  }

  public String getMessage(InstanceEvent event, Instance instance) {
    Map<String, String> root = new HashMap<>();
    root.put("id", event.getInstance().getValue());
    root.put("name", instance.getRegistration().getName());
    root.put("status", ((InstanceStatusChangedEvent) event).getStatusInfo().getStatus());

    StandardEvaluationContext context = new StandardEvaluationContext(root);
    context.addPropertyAccessor(new MapAccessor());
    String message = (String) parser.parseExpression(DEFAULT_MESSAGE, new TemplateParserContext())
        .getValue(context, String.class);

    return message;
  }

  public void pushMessage(String msg) {
    RestTemplate restTemplate = new RestTemplate();
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
    headers.add("Authorization",
        String.format("%s %s", "Bearer", lineProperties.getChannelToken()));

    HashMap object = new HashMap<>();
    object.put("to", lineProperties.getTo());
    List messages = new ArrayList();
    HashMap message = new HashMap<>();
    message.put("type", "text");
    message.put("text", msg);
    messages.add(message);
    object.put("messages", messages);

    HttpEntity<HashMap> entity = new HttpEntity<HashMap>(object, headers);
    ResponseEntity<String> response = restTemplate.exchange(
        "https://api.line.me/v2/bot/message/push",
        HttpMethod.POST, entity, String.class);
    if (response.getStatusCode().is2xxSuccessful()) {
      //System.out.println(response.getBody());
      log.info("Push message - " + msg);
      log.info("Push message - " + response.getStatusCode().toString());
    } else {
      log.info("Push message - failed");
    }
  }
}
