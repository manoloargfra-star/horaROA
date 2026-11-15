package es.manolo.horaroa;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final long REFRESCO_MS = 50;
    private static final long RESINCRONIZAR_MS = 5 * 60 * 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService red = Executors.newSingleThreadExecutor();
    private final Locale es = new Locale("es", "ES");

    private SimpleDateFormat fmtHora, fmtFecha, fmtCanarias;

    private TextView tvRoa, tvRoaDecimas, tvFecha, tvCanarias;
    private TextView tvMovil, tvMovilDecimas, tvDiferencia, tvEstado;
    private Button btnSincronizar;

    private volatile SntpClient.Resultado sync;
    private boolean sincronizando;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            actualizarPantalla();
            handler.postDelayed(this, REFRESCO_MS);
        }
    };

    private final Runnable autoSync = new Runnable() {
        @Override public void run() {
            sincronizar();
            handler.postDelayed(this, RESINCRONIZAR_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        TimeZone madrid = TimeZone.getTimeZone("Europe/Madrid");
        fmtHora = new SimpleDateFormat("HH:mm:ss", es);
        fmtHora.setTimeZone(madrid);
        fmtFecha = new SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy · zzzz", es);
        fmtFecha.setTimeZone(madrid);
        fmtCanarias = new SimpleDateFormat("HH:mm:ss", es);
        fmtCanarias.setTimeZone(TimeZone.getTimeZone("Atlantic/Canary"));

        tvRoa = findViewById(R.id.tvRoa);
        tvRoaDecimas = findViewById(R.id.tvRoaDecimas);
        tvFecha = findViewById(R.id.tvFecha);
        tvCanarias = findViewById(R.id.tvCanarias);
        tvMovil = findViewById(R.id.tvMovil);
        tvMovilDecimas = findViewById(R.id.tvMovilDecimas);
        tvDiferencia = findViewById(R.id.tvDiferencia);
        tvEstado = findViewById(R.id.tvEstado);
        btnSincronizar = findViewById(R.id.btnSincronizar);

        btnSincronizar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sincronizar(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
        handler.post(autoSync);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(autoSync);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        red.shutdownNow();
    }

    private void sincronizar() {
        if (sincronizando) return;
        sincronizando = true;
        btnSincronizar.setEnabled(false);
        tvEstado.setText(getString(R.string.sincronizando));

        red.execute(new Runnable() {
            @Override public void run() {
                SntpClient.Resultado r = null;
                String error = null;
                try {
                    r = new SntpClient().sincronizar(4);
                } catch (Exception e) {
                    error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                }
                final SntpClient.Resultado res = r;
                final String err = error;
                handler.post(new Runnable() {
                    @Override public void run() {
                        sincronizando = false;
                        btnSincronizar.setEnabled(true);
                        if (res != null) {
                            sync = res;
                        } else {
                            tvEstado.setText(getString(R.string.error_sync, err));
                        }
                    }
                });
            }
        });
    }

    private void actualizarPantalla() {
        long movil = System.currentTimeMillis();
        tvMovil.setText(fmtHora.format(new Date(movil)));
        tvMovilDecimas.setText("," + (movil % 1000) / 100);

        SntpClient.Resultado s = sync;
        if (s == null) return;

        long transcurrido = SystemClock.elapsedRealtime() - s.elapsedMs;
        long roa = s.horaRoaMs + transcurrido;
        Date dRoa = new Date(roa);

        tvRoa.setText(fmtHora.format(dRoa));
        tvRoaDecimas.setText("," + (roa % 1000) / 100);
        tvFecha.setText(capitalizar(fmtFecha.format(dRoa)));
        tvCanarias.setText(getString(R.string.canarias, fmtCanarias.format(dRoa)));

        long dif = movil - roa; // se recalcula: detecta si el usuario cambia la hora del móvil
        String segundos = String.format(es, "%.3f s", Math.abs(dif) / 1000.0);
        if (Math.abs(dif) < 50) {
            tvDiferencia.setText(getString(R.string.dif_en_hora, segundos));
        } else if (dif > 0) {
            tvDiferencia.setText(getString(R.string.dif_adelanta, segundos));
        } else {
            tvDiferencia.setText(getString(R.string.dif_atrasa, segundos));
        }

        if (!sincronizando) {
            tvEstado.setText(getString(R.string.estado, s.retardoMs, s.estrato, transcurrido / 1000));
        }
    }

    private static String capitalizar(String t) {
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }
}
