package edu.harvard.hms.dbmi.avillach.auth.data.repository;

import edu.harvard.dbmi.avillach.data.repository.BaseRepository;
import edu.harvard.hms.dbmi.avillach.auth.data.entity.Application;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import java.util.UUID;

/**
 * <p>Provides operations for the Application entity to interact with a database.</p>>
 *
 * @see Application
 */
@ApplicationScoped
@Transactional
public class ApplicationRepository extends BaseRepository<Application, UUID> {

    protected ApplicationRepository() {
        super(Application.class);
    }
}
