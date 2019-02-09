package edu.harvard.hms.dbmi.avillach.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import edu.harvard.hms.dbmi.avillach.auth.JAXRSConfiguration;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;

import org.hibernate.mapping.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

public class MailService {
    private Logger logger = LoggerFactory.getLogger(MailService.class);
    private MustacheFactory mf = new DefaultMustacheFactory();
    Mustache accessEmail = mf.compile("accessEmail.mustache");

    //TODO Where/how to store this for real?
    private String systemName = System.getenv("java:global/systemName");

    public void sendUsersAccessEmail(User user){
        try {
            Message message = new MimeMessage(JAXRSConfiguration.mailSession);
            String email = user.getEmail();
            if(email == null) {
            		HashMap<String,String> metadata = new HashMap<String, String>((java.util.Map<? extends String, ? extends String>) new ObjectMapper().readValue(user.getGeneralMetadata(), Map.class));
            		List<String> emailKeys = metadata.keySet().stream().filter((key)->{return key.toLowerCase().contains("email");}).collect(Collectors.toList());
            		if(emailKeys.size()>0) {
            			email = metadata.get(emailKeys.get(0));
            		}
            }
			if (email != null){
                message.setRecipient(Message.RecipientType.TO, new InternetAddress(email));
                //TODO Is the subject configurable as well?  What should it say?
                message.setSubject("Your Access To " + systemName);
                String body = generateBody(user);
                message.setText(body);
                Transport.send(message);
            } else {
                logger.error("User " + user.getSubject() + " has no email");
            }
        } catch (MessagingException e){
            //TODO: Freak out
        } catch (Exception e){
            logger.error(e.getMessage(), e);
        }
    }

    public String generateBody(User u) {
        Writer writer = accessEmail.execute(new StringWriter(),new AccessEmail(u));
        return writer.toString();
    }
}
