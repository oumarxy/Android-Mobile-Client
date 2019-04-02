package io.intelehealth.client.services;

import android.app.IntentService;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

import io.intelehealth.client.R;
import io.intelehealth.client.activities.setting_activity.SettingsActivity;
import io.intelehealth.client.application.IntelehealthApplication;
import io.intelehealth.client.dao.VisitSummaryDAO;
import io.intelehealth.client.database.DelayedJobQueueProvider;
import io.intelehealth.client.database.LocalRecordsDatabaseHelper;
import io.intelehealth.client.objects.Obs;
import io.intelehealth.client.objects.Patient;
import io.intelehealth.client.objects.WebResponse;
import io.intelehealth.client.utilities.ConceptId;
import io.intelehealth.client.utilities.EmergencyEncounter;
import io.intelehealth.client.utilities.HelperMethods;
import io.intelehealth.client.utilities.UuidDictionary;

import static io.intelehealth.client.services.ClientService.STATUS_SYNC_IN_PROGRESS;
import static io.intelehealth.client.services.ClientService.STATUS_SYNC_STOPPED;

/**
 * An {@link IntentService} subclass for handling asynchronous task requests in
 * a service on a separate handler thread.
 * <p>
 * TODO: Customize class - update intent actions, extra parameters and static
 * helper methods.
 */
public class UpdateVisitService extends IntentService {

    LocalRecordsDatabaseHelper mDbHelper;
    SQLiteDatabase db;

    public UpdateVisitService() {
        super(TAG);
    }

    private String visitId;
    private String visitStartDateTime;
    private String patientUUID;
    private String visitUUID;
        private Integer patientID;

    NotificationManager mNotifyManager;
    public int mId = 5;
    NotificationCompat.Builder mBuilder;

    String quote = "\"";

    Integer queueId = null;

    private String encounterAdultInitial, encounterVitals;

    ArrayList<Obs> obsArrayList; //Contains Obs that are updatable

    private static final String TAG = UpdateVisitService.class.getSimpleName();
    boolean hasLicense = false;
    SharedPreferences.Editor e;
    SharedPreferences sharedPreferences;
    String channelId = "channel-01";
    String channelName = "Channel Name";

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(IntelehealthApplication.getAppContext());
    String location_uuid = prefs.getString(SettingsActivity.KEY_PREF_LOCATION_UUID, null);
    String provider_uuid = prefs.getString("providerid", null);
    VisitSummaryDAO visitSummaryDAO =new VisitSummaryDAO();
    EmergencyEncounter emergencyEncounter = new EmergencyEncounter();

    @Override
    protected void onHandleIntent(Intent intent) {


        mNotifyManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        //mahiti added
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel mChannel = new NotificationChannel(
                    channelId, channelName, importance);
            mNotifyManager.createNotificationChannel(mChannel);
        }
        mBuilder = new NotificationCompat.Builder(this,channelId);
        mDbHelper = new LocalRecordsDatabaseHelper(this.getApplicationContext());
        db = mDbHelper.getWritableDatabase();

        String text = String.format("Uploading %s's visit data", intent.getStringExtra("name"));
        createNotification(text);

        if (!intent.hasExtra("queueId")) {
            int id = addJobToQueue(intent);
            intent.putExtra("queueId", id);
        }

        if (intent != null) {
            visitId = intent.getStringExtra("visitID");
            patientID = intent.getIntExtra("patientID", -1);
        }

        Log.i(TAG, "onHandleIntent: " + visitId);

        if (visitId != null && !visitId.isEmpty()) {

            queueId = intent.getIntExtra("queueId", -1);
            queueSyncStart(queueId);

            createNotification("");

            String selection = "_id = ?";
            String[] coloumns = {"start_datetime", "openmrs_visit_uuid"};
            String[] args = {String.valueOf(visitId)};

            Cursor visitCursor = db.query("visit", coloumns, selection, args, null, null, null);

            if (visitCursor != null && visitCursor.moveToFirst() && visitCursor.getCount() > 0) {
                visitStartDateTime = visitCursor.getString(visitCursor.getColumnIndex("start_datetime"));
                visitUUID = visitCursor.getString(visitCursor.getColumnIndex("openmrs_visit_uuid"));
            }

            visitCursor.close();

            if (patientID != null) {

                String selection_patient = "_id = ?";
                String[] coloumns_patient = {"openmrs_uuid"};
                String[] args_patient = {String.valueOf(patientID)};

                Cursor patientCursor = db.query("patient", coloumns_patient, selection_patient, args_patient, null, null, null);

                if (patientCursor != null && patientCursor.moveToFirst() && patientCursor.getCount() > 0) {
                    patientUUID = patientCursor.getString(patientCursor.getColumnIndex("openmrs_uuid"));
                }

                patientCursor.close();

                Intent imageUpload = new Intent(this, ImageUploadService.class);
                imageUpload.putExtra("patientID", patientID);
                imageUpload.putExtra("name", intent.getStringExtra("name"));
                imageUpload.putExtra("patientUUID", patientUUID);
                imageUpload.putExtra("visitUUID", visitUUID);
                imageUpload.putExtra("visitID", visitId);
                startService(imageUpload);
            }

            queryEncounterTable(visitId);
            queryObsTable(visitId);

            boolean check = true;
            boolean check_all = true;
            sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            if (sharedPreferences.contains("licensekey"))
                hasLicense = true;
            //Check for license key and load the correct config file
            if (obsArrayList != null && !obsArrayList.isEmpty()) {
                for (Obs obs : obsArrayList) {

                    Log.i(TAG, "onHandleIntent: " + obs.getConceptId() + ":" + obs.getValue());

                    Integer concept_id = obs.getConceptId();
                    switch (concept_id) {
                        case ConceptId.WEIGHT: {
                            if (obs.getOpenmrsObsId() != null && !obs.getOpenmrsObsId().equals(0)) {
                                check = updateObs(UuidDictionary.WEIGHT, obs.getValue(),
                                        obs.getOpenmrsObsId(), encounterVitals);
                                if (!check) check_all = false;
                            } else {
                                check = createObs(encounterVitals, UuidDictionary.WEIGHT, obs.getValue(),
                                        String.valueOf(obs.getConceptId()));
                                if (!check) check_all = false;
                            }
                            break;
                        }
                        case ConceptId.HEIGHT: {
                            if (obs.getOpenmrsObsId() != null && !obs.getOpenmrsObsId().equals(0)) {
                                check = updateObs(UuidDictionary.HEIGHT, obs.getValue(),
                                        obs.getOpenmrsObsId(), encounterVitals);
                                if (!check) check_all = false;
                            } else {
                                check = createObs(encounterVitals, UuidDictionary.HEIGHT, obs.getValue(),
                                        String.valueOf(obs.getConceptId()));
                                if (!check) check_all = false;
                            }
                            break;
                        }
                        case ConceptId.PULSE: {
                            if (obs.getOpenmrsObsId() != null && !obs.getOpenmrsObsId().equals(0)) {
                                check = updateObs(UuidDictionary.PULSE, obs.getValue(),
                                        obs.getOpenmrsObsId(), encounterVitals);
                                if (!check) check_all = false;
                            } else {
                                check = createObs(encounterVitals, UuidDictionary.PULSE, obs.getValue(),
                                        String.valueOf(obs.getConceptId()));
                                if (!check) check_all = false;
                            }
                            break;
                        }
                        case ConceptId.SYSTOLIC_BP: {
                            if (obs.getOpenmrsObsId() != null && !obs.getOpenmrsObsId().equals(0)) {
                                check = updateObs(UuidDictionary.SYSTOLIC_BP, obs.getValue(),
                                        obs.getOpenmrsObsId(), encounterVitals);
                                if (!check) check_all = false;
                            } else {
                                check = createObs(encounterVitals, UuidDictionary.SYSTOLIC_BP, obs.getValue(),
                                        String.valueOf(obs.getConceptId()));
                                if (!check) check_all = false;
                            }
                            break;
                        }
                        case ConceptId.DIASTOLIC_BP: {
                            if (obs.getOpenmrsObsId() != null && !obs.getOpenmrsObsId().equals(0)) {
                                check = updateObs(UuidDictionary.DIASTOLIC_BP, obs.getValue(),
                                        obs.getOpenmrsObsId(), encounterVitals);
                                if (!check) check_all = false;
                            } else {
                                check = createObs(encounterVitals, UuidDictionary.DIASTOLIC_BP, obs.getValue(),
                                        String.valueOf(obs.getConceptId()));
                                if (!check) check_all = false;
                            }
                            break;
                        }
                        case ConceptId.TEMPERATURE: {
                            if (obs.getValue() != null && !obs.getValue().trim().isEmpty()) {
                                try {
                                    JSONObject obj = null;
                                    String mFileName="config.json";
                                    if (hasLicense) {
                                        obj = new JSONObject(HelperMethods.readFileRoot(mFileName, this)); //Load the config file
                                    }else {
                                        obj = new JSONObject(String.valueOf(HelperMethods.encodeJSON(this, mFileName)));
                                    }
                                    if (obj.getBoolean("mCelsius")) {
                                        try {
                                            Double fTemp = Double.parseDouble(obs.getValue());
//                                            Double cTemp = ((fTemp - 32) * 5 / 9);

                                            if (obs.getOpenmrsObsId() != null && !obs.getOpenmrsObsId().equals(0)) {

                                                check = updateObs(UuidDictionary.TEMPERATURE, String.valueOf(fTemp),
                                                        obs.getOpenmrsObsId(), encounterVitals);
                                                if (!check) check_all = false;
                                            } else {
                                                check = createObs(encounterVitals, UuidDictionary.TEMPERATURE, String.valueOf(fTemp),
                                                        String.valueOf(obs.getConceptId()));
                                                if (!check) check_all = false;
                                            }
                                        } catch (NumberFormatException e) {
                                            Log.e(TAG, "onHandleIntent: ", e);
                                        }

                                    } else if (obj.getBoolean("mFahrenheit")) {
                                        try {
                                            Double fTemp = Double.parseDouble(obs.getValue());
                                            Double cTemp = ((fTemp - 32) * 5 / 9);

                                            if (obs.getOpenmrsObsId() != null && !obs.getOpenmrsObsId().equals(0)) {

                                                check = updateObs(UuidDictionary.TEMPERATURE, String.valueOf(cTemp),
                                                        obs.getOpenmrsObsId(), encounterVitals);
                                                if (!check) check_all = false;
                                            } else {
                                                check = createObs(encounterVitals, UuidDictionary.TEMPERATURE, String.valueOf(cTemp),
                                                        String.valueOf(obs.getConceptId()));
                                                if (!check) check_all = false;
                                            }
                                        } catch (NumberFormatException e) {
                                            Log.e(TAG, "onHandleIntent: ", e);
                                        }
                                    }


                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }
                            }
                            break;
                        }
                        //    Respiratory added by mahiti dev team
                        case ConceptId.RESPIRATORY: {
                            if (obs.getValue() != null && !obs.getValue().trim().isEmpty()) {
                                try {
                                    Double fTemp = Double.parseDouble(obs.getValue());
//                                    Double cTemp = ((fTemp * 9 / 5) + 32);
                                    if (obs.getOpenmrsObsId() != null && !obs.getOpenmrsObsId().equals(0)) {

                                        check = updateObs(UuidDictionary.RESPIRATORY, String.valueOf(fTemp),
                                                obs.getOpenmrsObsId(), encounterVitals);
                                        if (!check) check_all = false;
                                    } else {
                                        check = createObs(encounterVitals, UuidDictionary.RESPIRATORY, String.valueOf(fTemp),
                                                String.valueOf(obs.getConceptId()));
                                        if (!check) check_all = false;
                                    }
                                } catch (NumberFormatException e) {
                                    Log.e(TAG, "onHandleIntent: ", e);
                                }
                            }
                            break;
                        }
                        case ConceptId.SPO2: {
                            if (obs.getOpenmrsObsId() != null && !obs.getOpenmrsObsId().equals(0)) {
                                check = updateObs(UuidDictionary.SPO2, obs.getValue(),
                                        obs.getOpenmrsObsId(), encounterVitals);
                                if (!check) check_all = false;
                            } else {
                                check = createObs(encounterVitals, UuidDictionary.SPO2, obs.getValue(),
                                        String.valueOf(obs.getConceptId()));
                                if (!check) check_all = false;
                            }
                            break;
                        }
                        case ConceptId.PHYSICAL_EXAMINATION: {
                            if (obs.getOpenmrsObsId() != null && !obs.getOpenmrsObsId().equals(0)) {
                                check = updateObs(UuidDictionary.PHYSICAL_EXAMINATION, obs.getValue(),
                                        obs.getOpenmrsObsId(), encounterAdultInitial);
                                if (!check) check_all = false;
                            } else {
                                check = createObs(encounterAdultInitial, UuidDictionary.PHYSICAL_EXAMINATION, obs.getValue(),
                                        String.valueOf(obs.getConceptId()));
                                if (!check) check_all = false;
                            }
                            break;
                        }
                        case ConceptId.CURRENT_COMPLAINT: {
                            if (obs.getOpenmrsObsId() != null && !obs.getOpenmrsObsId().equals(0)) {
                                check = updateObs(UuidDictionary.CURRENT_COMPLAINT, obs.getValue(),
                                        obs.getOpenmrsObsId(), encounterAdultInitial);
                                if (!check) check_all = false;
                            } else {
                                check = createObs(encounterAdultInitial, UuidDictionary.CURRENT_COMPLAINT, obs.getValue(),
                                        String.valueOf(obs.getConceptId()));
                                if (!check) check_all = false;
                            }
                            break;
                        }
                        case ConceptId.RHK_MEDICAL_HISTORY_BLURB: {
                            if (obs.getOpenmrsObsId() != null && !obs.getOpenmrsObsId().equals(0)) {
                                check = updateObs(UuidDictionary.RHK_MEDICAL_HISTORY_BLURB, obs.getValue(),
                                        obs.getOpenmrsObsId(), encounterAdultInitial);
                                if (!check) check_all = false;
                            } else {
                                check = createObs(encounterAdultInitial, UuidDictionary.RHK_MEDICAL_HISTORY_BLURB, obs.getValue(),
                                        String.valueOf(obs.getConceptId()));
                                if (!check) check_all = false;
                            }
                            break;
                        }
                        case ConceptId.RHK_FAMILY_HISTORY_BLURB: {
                            if (obs.getOpenmrsObsId() != null && !obs.getOpenmrsObsId().equals(0)) {
                                check = updateObs(UuidDictionary.RHK_FAMILY_HISTORY_BLURB, obs.getValue(),
                                        obs.getOpenmrsObsId(), encounterAdultInitial);
                                if (!check) check_all = false;
                            } else {
                                check = createObs(encounterAdultInitial, UuidDictionary.RHK_FAMILY_HISTORY_BLURB, obs.getValue(),
                                        String.valueOf(obs.getConceptId()));
                                if (!check) check_all = false;
                            }
                            break;
                        }
                        default:
                    }

                }
            }
            queueSyncStop(queueId);

            if (check_all) {
                text = String.format("%s's visit data upload successful", intent.getStringExtra("name"));
                createNotification(text);
                removeJobFromQueue(queueId);
            } else {
                text = String.format("%s's visit data upload unsuccessful", intent.getStringExtra("name"));
                createNotification(text);
            }
        }
    }


    private void queryEncounterTable(String visitId) {

        String selection = "visit_id = ?";
        String[] args = {visitId};
        String[] coloumns = {"_id", "openmrs_encounter_id", "encounter_type "};

        try {
            Cursor encounterCursor = db.query("encounter", coloumns, selection, args, null, null, null);

            if (encounterCursor != null && encounterCursor.moveToFirst()) {
                do {

                    String encounterType = encounterCursor.getString(encounterCursor.getColumnIndexOrThrow("encounter_type"));
                    Log.i(TAG, "queryEncounterTable: " + encounterType);

                    switch (encounterType) {
                        case "ADULTINITIAL": {
                            encounterAdultInitial = encounterCursor.getString(encounterCursor.getColumnIndexOrThrow("openmrs_encounter_id"));
                            Log.i(TAG, "queryEncounterTable: " + encounterAdultInitial);
                            break;
                        }
                        case "EMERGECY": {
                            encounterAdultInitial = encounterCursor.getString(encounterCursor.getColumnIndexOrThrow("openmrs_encounter_id"));
                            Log.i(TAG, "Emergency queryEncounterTable: " + encounterAdultInitial);
                            break;
                        }
                        case "VITALS": {
                            encounterVitals = encounterCursor.getString(encounterCursor.getColumnIndexOrThrow("openmrs_encounter_id"));
                            Log.i(TAG, "queryEncounterTable: " + encounterVitals);
                            break;
                        }

                        default: {

                        }
                    }
                } while (encounterCursor.moveToNext());

            }

            encounterCursor.close();

        } catch (SQLException e) {
            Log.d(TAG, "queryEncounterTable: " + e.getMessage());
        }

    }

    private void queryObsTable(String visitId) {

        obsArrayList = new ArrayList<>();

        String selection = "visit_id = ?";
        String[] args = new String[1];
        args[0] = visitId;

        try {
            Cursor obsCursor = db.query("obs", null, selection, args, null, null, null);


            if (obsCursor != null && obsCursor.moveToFirst() && obsCursor.getCount() > 0) {
                do {

                    String value = obsCursor.getString(obsCursor.getColumnIndexOrThrow("value"));
                    if (value != null && !value.isEmpty()) {
                        Obs obs = new Obs();
                        Integer concept_id = obsCursor.getInt(obsCursor.getColumnIndexOrThrow("concept_id"));
                        String obs_id = obsCursor.getString(obsCursor.getColumnIndexOrThrow("openmrs_obs_id"));
                        String encounter_id = obsCursor.getString(obsCursor.getColumnIndexOrThrow("openmrs_encounter_id"));
                        switch (concept_id) {
                            case ConceptId.WEIGHT: {
                                if (!value.equals("0")) {
                                    obs.setConceptId(concept_id);
                                    obs.setValue(value);
                                    obs.setOpenmrsObsId(obs_id);
                                    obs.setOpenmrsEncounterId(encounter_id);
                                    obsArrayList.add(obs);
                                }
                                break;
                            }
                            case ConceptId.HEIGHT: {
                                if (!value.equals("0")) {
                                    obs.setConceptId(concept_id);
                                    obs.setValue(value);
                                    obs.setOpenmrsObsId(obs_id);
                                    obs.setOpenmrsEncounterId(encounter_id);
                                    obsArrayList.add(obs);
                                }
                                break;
                            }
                            case ConceptId.PULSE: {
                                if (!value.equals("0")) {
                                    obs.setConceptId(concept_id);
                                    obs.setValue(value);
                                    obs.setOpenmrsObsId(obs_id);
                                    obs.setOpenmrsEncounterId(encounter_id);
                                    obsArrayList.add(obs);
                                }
                                break;
                            }
                            case ConceptId.SYSTOLIC_BP: {
                                if (!value.equals("0")) {
                                    obs.setConceptId(concept_id);
                                    obs.setValue(value);
                                    obs.setOpenmrsObsId(obs_id);
                                    obs.setOpenmrsEncounterId(encounter_id);
                                    obsArrayList.add(obs);
                                }
                                break;
                            }
                            case ConceptId.DIASTOLIC_BP: {
                                if (!value.equals("0")) {
                                    obs.setConceptId(concept_id);
                                    obs.setValue(value);
                                    obs.setOpenmrsObsId(obs_id);
                                    obs.setOpenmrsEncounterId(encounter_id);
                                    obsArrayList.add(obs);
                                }
                                break;
                            }
                            case ConceptId.TEMPERATURE: {
                                if (!value.equals("0")) {
                                    obs.setConceptId(concept_id);
                                    obs.setValue(value);
                                    obs.setOpenmrsObsId(obs_id);
                                    obs.setOpenmrsEncounterId(encounter_id);
                                    obsArrayList.add(obs);
                                }
                                break;
                            }
                            case ConceptId.RESPIRATORY: {
                                if (!value.equals("0")) {
                                    obs.setConceptId(concept_id);
                                    obs.setValue(value);
                                    obs.setOpenmrsObsId(obs_id);
                                    obs.setOpenmrsEncounterId(encounter_id);
                                    obsArrayList.add(obs);
                                }
                                break;
                            }
                            case ConceptId.SPO2: {
                                if (!value.equals("0")) {
                                    obs.setConceptId(concept_id);
                                    obs.setValue(value);
                                    obs.setOpenmrsObsId(obs_id);
                                    obs.setOpenmrsEncounterId(encounter_id);
                                    obsArrayList.add(obs);
                                }
                                break;
                            }
                            case ConceptId.CURRENT_COMPLAINT: {
                                obs.setConceptId(concept_id);
                                obs.setValue(value);
                                obs.setOpenmrsObsId(obs_id);
                                obs.setOpenmrsEncounterId(encounter_id);
                                obsArrayList.add(obs);
                                break;
                            }
                            case ConceptId.RHK_FAMILY_HISTORY_BLURB: {
                                obs.setConceptId(concept_id);
                                obs.setValue(value);
                                obs.setOpenmrsObsId(obs_id);
                                obs.setOpenmrsEncounterId(encounter_id);
                                obsArrayList.add(obs);
                                break;
                            }
                            case ConceptId.PHYSICAL_EXAMINATION: {
                                obs.setConceptId(concept_id);
                                obs.setValue(value);
                                obs.setOpenmrsObsId(obs_id);
                                obs.setOpenmrsEncounterId(encounter_id);
                                obsArrayList.add(obs);
                                break;
                            }
                            case ConceptId.RHK_MEDICAL_HISTORY_BLURB: {
                                obs.setConceptId(concept_id);
                                obs.setValue(value);
                                obs.setOpenmrsObsId(obs_id);
                                obs.setOpenmrsEncounterId(encounter_id);
                                obsArrayList.add(obs);
                                break;
                            }
                            default:
                        }
                    }
                } while (obsCursor.moveToNext());
            }
            obsCursor.close();
        } catch (SQLException e)

        {
            Log.d(TAG, "queryObsTable: " + e.getMessage());
        }

    }

    private boolean createObs(String encounterUUID, String conceptUUID, String value, String concept_id) {


        String obsCreateString =
                String.format("{" + quote + "concept" + quote + ":" + quote + "%s" + quote + "," +
                                quote + "person" + quote + ":" + quote + "%s" + quote + "," +
                                quote + "obsDatetime" + quote + ":" + quote + "%s" + quote + "," +
                                quote + "encounter" + quote + ":" + quote + "%s" + quote + "," +
                                quote + "value" + quote + ":" + quote + "%s" + quote +
                                "}",
                        conceptUUID,
                        patientUUID,
                        visitStartDateTime,
                        encounterUUID,
                        value
                );

        Log.i(TAG, "createObs: " + obsCreateString);

        WebResponse responseObs;
        responseObs = HelperMethods.postCommand("obs", obsCreateString, this);
//        added to avoid crash based on #649 issue
        if (responseObs != null) {
            Log.d(TAG, String.valueOf(responseObs.getResponseCode()));
        }
        if (responseObs == null || responseObs.getResponseCode() != 201) {
            Log.d(TAG, "Obs posting was unsuccessful");
            return false;
        } else {
            Log.d(TAG, responseObs.getResponseString());
            Log.d(TAG, responseObs.getResponseObject());

            if (visitId != null && concept_id != null) {

                String obsUpdateSelection = "visit_id = ? AND concept_id = ?";

                try {
                    JSONObject response = new JSONObject(responseObs.getResponseObject());

                    String display = response.getString("display");
                    String obsUUID = response.getString("uuid");

                    String[] obsUpdateArgs = {String.valueOf(visitId), concept_id};
                    ContentValues contentValuesObs = new ContentValues();
                    contentValuesObs.put("openmrs_encounter_id", encounterUUID);
                    contentValuesObs.put("openmrs_obs_id", obsUUID);
                    db.update(
                            "obs",
                            contentValuesObs,
                            obsUpdateSelection,
                            obsUpdateArgs
                    );
                } catch (JSONException e) {
                    e.printStackTrace();
                    return false;
                }
                String query = "Select ifnull(emergency,'') as emergency FROM visit WHERE _id = " + visitId + "";
                Cursor cursor=db.rawQuery(query,null);
                if(cursor!=null) {
                    while(cursor.moveToNext()) {
                        String emergency = cursor.getString(cursor.getColumnIndex("emergency"));
                        if (emergency.equalsIgnoreCase("true")) {

                            if(visitSummaryDAO.getEmergencyUUID(visitId,db).isEmpty()) {
                                emergencyEncounter.uploadEncounterEmergency(visitId, visitUUID, visitStartDateTime, patientID, db, getApplicationContext());
                            }
                            }
                    }
                    cursor.close();
                }
                return true;
            }
            return false;
        }
    }


    private boolean updateObs(String conceptUUID, String value, String openmrsObsId, String encounterUUID) {

        String updateObs = "obs/" + openmrsObsId;

        String obsUpdateString =
                String.format("{" + quote + "concept" + quote + ":" + quote + "%s" + quote + "," +
                                quote + "person" + quote + ":" + quote + "%s" + quote + "," +
                                quote + "obsDatetime" + quote + ":" + quote + "%s" + quote + "," +
                                quote + "encounter" + quote + ":" + quote + "%s" + quote + "," +
                                quote + "value" + quote + ":" + quote + "%s" + quote +
                                "}",
                        conceptUUID,
                        patientUUID,
                        visitStartDateTime,
                        encounterUUID,
                        value
                );

        Log.i(TAG, "updateObs: " + obsUpdateString);

        WebResponse responseObs;
        responseObs = HelperMethods.postCommand(updateObs, obsUpdateString, getApplicationContext());
        Log.d(TAG, String.valueOf(responseObs.getResponseCode()));
        if (responseObs == null || responseObs.getResponseCode() != 200) {
            Log.d(TAG, "Obs Update posting was unsuccessful");
            return false;
        } else {
            Log.d(TAG, "Obs Update posting was successful");

            Log.d(TAG, responseObs.getResponseString());
            Log.d(TAG, responseObs.getResponseObject());

            String obsUpdateSelection = "openmrs_obs_id = ?";

            try {
                JSONObject response = new JSONObject(responseObs.getResponseObject());
                String obsUUID = response.getString("uuid");

                String[] obsUpdateArgs = {openmrsObsId};
                ContentValues contentValuesObs = new ContentValues();
                contentValuesObs.put("openmrs_obs_id", obsUUID);
                db.update(
                        "obs",
                        contentValuesObs,
                        obsUpdateSelection,
                        obsUpdateArgs
                );
            } catch (JSONException e) {
                e.printStackTrace();
            }

            String query = "Select ifnull(emergency,'') as emergency FROM visit WHERE _id = " + visitId + "";
            Cursor cursor=db.rawQuery(query,null);
            if(cursor!=null) {
                while(cursor.moveToNext()) {
                    String emergency = cursor.getString(cursor.getColumnIndex("emergency"));
                    if (emergency.equalsIgnoreCase("true")) {

                        if(visitSummaryDAO.getEmergencyUUID(visitId,db).isEmpty()) {
                            emergencyEncounter.uploadEncounterEmergency(visitId, visitUUID, visitStartDateTime, patientID, db, getApplicationContext());
                        }
                    }
                }
                cursor.close();
            }


            return true;
        }


    }

    private int addJobToQueue(Intent intent) {

        Log.d(TAG, "Adding to Queue");
        // Add a new Delayed Job record
        ContentValues values = new ContentValues();
        values.put(DelayedJobQueueProvider.JOB_TYPE, "obsUpdate");
        values.put(DelayedJobQueueProvider.JOB_PRIORITY, 1);
        values.put(DelayedJobQueueProvider.JOB_REQUEST_CODE, 0);
        values.put(DelayedJobQueueProvider.PATIENT_NAME, intent.getStringExtra("name"));
        values.put(DelayedJobQueueProvider.PATIENT_ID, intent.getIntExtra("patientID", -1));
        values.put(DelayedJobQueueProvider.VISIT_ID, intent.getStringExtra("visitID"));
        values.put(DelayedJobQueueProvider.SYNC_STATUS, 0);

        Uri uri = getContentResolver().insert(
                DelayedJobQueueProvider.CONTENT_URI, values);

        return Integer.valueOf(uri.getLastPathSegment());

    }

    private void removeJobFromQueue(int queueId) {
        Log.d(TAG, "Removing from Queue");
        if (queueId > -1) {
            String url = DelayedJobQueueProvider.URL + "/" + queueId;
            Uri uri = Uri.parse(url);
            int result = getContentResolver().delete(uri, null, null);
            if (result > 0) {
                Log.i(TAG, result + " row deleted");
            } else {
                Log.e(TAG, "Database error while deleting row!");
            }
        }

    }

    private void queueSyncStart(int queueId) {
        ContentValues values = new ContentValues();
        values.put(DelayedJobQueueProvider.SYNC_STATUS, STATUS_SYNC_IN_PROGRESS);
        String url = DelayedJobQueueProvider.URL + "/" + queueId;
        Uri uri = Uri.parse(url);
        getContentResolver().update(uri, values, null, null);
    }

    private void queueSyncStop(int queueId) {
        ContentValues values = new ContentValues();
        values.put(DelayedJobQueueProvider.SYNC_STATUS, STATUS_SYNC_STOPPED);
        String url = DelayedJobQueueProvider.URL + "/" + queueId;
        Uri uri = Uri.parse(url);
        int result = getContentResolver().update(uri, values, null, null);
    }

    private void createNotification(String message) {
        mBuilder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Visit Data Update")
                .setContentText(message);
        mNotifyManager.notify(mId, mBuilder.build());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

}
