package com.gptdrive.app;

import android.content.Context;
import android.provider.DocumentsContract;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.List;

public final class NewFileWatchWorker extends Worker {
    public NewFileWatchWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        DriveDb db = new DriveDb(context);
        List<AccountProfile> accounts = db.listAccounts();
        boolean retry = false;
        for (AccountProfile account : accounts) {
            if (account == null || account.id <= 0 || !account.hasSession()) continue;
            try {
                ChatGptApiClient client = new ChatGptApiClient(context, db, account.id);
                client.sync((phase, part, total, objects) -> {});
            } catch (ChatGptApiClient.AuthException ignored) {
            } catch (Exception e) {
                retry = true;
            }
        }
        try {
            context.getContentResolver().notifyChange(
                    DocumentsContract.buildRootsUri("com.gptdrive.app.documents"), null);
        } catch (Exception ignored) {}
        return retry ? Result.retry() : Result.success();
    }
}
