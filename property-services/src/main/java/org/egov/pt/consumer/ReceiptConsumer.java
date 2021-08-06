package org.egov.pt.consumer;

import java.util.HashMap;

import org.egov.pt.config.PropertyConfiguration;
import org.egov.pt.service.PaymentNotificationService;
import org.egov.pt.service.PaymentUpdateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;



@Component
public class ReceiptConsumer {

	@Autowired
    private PaymentUpdateService paymentUpdateService;

	@Autowired
    private PaymentNotificationService paymentNotificationService;

	@Autowired
    private PropertyConfiguration config;

    @KafkaListener(topics = {"${kafka.topics.receipt.create}","${kafka.topics.notification.pg.save.txns}"})
    public void listenPayments(final HashMap<String, Object> record,  @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
    //public void listenPayments(Object record,  @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {	
    	System.out.println("MESSAGE CONSUMED ");
        if(topic.equalsIgnoreCase(config.getReceiptTopic())){
        	System.out.println("Case 1");
            paymentUpdateService.process(record);
            System.out.println("UPDATE DONE");
            paymentNotificationService.process(record, topic);
        }
        else paymentNotificationService.process(record, topic);
         //System.out.println("Case 2");

    }
}
