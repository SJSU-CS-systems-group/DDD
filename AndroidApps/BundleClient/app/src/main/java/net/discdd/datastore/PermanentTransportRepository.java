package net.discdd.datastore;

import android.app.Application;
import org.checkerframework.checker.units.qual.A;

public class PermanentTransportRepository {
    private PermanentTransportDao permanentTransportDao;

    public PermanentTransportRepository(Application application) {
        AppDatabase db = AppDatabase.getInstance(application);
        permanentTransportDao = db.permanentTransportDao();
    }
}
