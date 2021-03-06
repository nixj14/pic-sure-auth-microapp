package edu.harvard.hms.dbmi.avillach.auth.service;

import edu.harvard.hms.dbmi.avillach.auth.data.entity.TermsOfService;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.TermsOfServiceRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.rest.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import javax.inject.Inject;
import javax.persistence.NoResultException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * <p>Provides business logic for the TermsOfService endpoint.</p>>
 *
 * @see edu.harvard.hms.dbmi.avillach.auth.rest.TermsOfServiceEndpoint
 */
public class TOSService {

    Logger logger = LoggerFactory.getLogger(TOSService.class);

    @Inject
    TermsOfServiceRepository termsOfServiceRepo;

    @Inject
    UserRepository userRepo;

    @Inject
    UserService userService;

    public boolean hasUserAcceptedLatest(String userId){
        logger.info("Checking Terms Of Service acceptance for user with id " + userId);
        return userRepo.checkAgainstTOSDate(userId);
    }

    public TermsOfService updateTermsOfService(String html){
        TermsOfService updatedTOS = new TermsOfService();
        updatedTOS.setContent(html);
        termsOfServiceRepo.persist(updatedTOS);
        return termsOfServiceRepo.getLatest();
    }

    public String getLatest(){
        try {
            return termsOfServiceRepo.getLatest().getContent();
        } catch (NoResultException e){
            logger.info("Terms Of Service disabled: No Terms of Service found in database");
            return null;
        }
    }

    public void acceptTermsOfService(String userId){
        logger.info("User " + userId + " accepting TOS");
        User user = userRepo.findBySubject(userId);
        if (user == null){
            throw new RuntimeException("User does not exist");
        }
        user.setAcceptedTOS(new Date());
        List<User> users = Arrays.asList(user);
        Date tosDate = termsOfServiceRepo.getLatest().getDateUpdated();
        userService.updateUser(users);
        logger.info("TOS_LOG : User " + (!StringUtils.isEmpty(user.getEmail()) ? user.getEmail() : user.getGeneralMetadata()) + " accepted the Terms of Service dated " + tosDate.toString());
    }
}
