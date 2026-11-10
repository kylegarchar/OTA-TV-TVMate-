/* hdtvmate-cli: scan / tune / stream with libhdtvmate.
 *   hdtvmate-cli scan                      RF 2-36: lock, signal, PSIP channel names
 *   hdtvmate-cli status
 *   hdtvmate-cli tune <rfN|kHz> [-o file|-] [-t secs]   stream the whole multiplex as MPEG-TS
 * Options: -b <hdtvmate.bin> */
#include "hdtvmate.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <signal.h>
#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

static volatile sig_atomic_t stop;
static void on_sig(int s) { (void)s; stop = 1; }

static uint32_t parse_freq(const char *a)
{
	if ((a[0] == 'r' || a[0] == 'R') && (a[1] == 'f' || a[1] == 'F'))
		return hdtvmate_channel_khz(atoi(a + 2));
	return (uint32_t)strtoul(a, NULL, 10);
}

/* ---- PSIP terrestrial virtual channel table (PID 0x1ffb, table 0xc8) ---- */
static int print_tvct(const uint8_t *s, int len)
{
	int n, o = 10, k, printed = 0;
	if (len < 10 || (s[0] != 0xc8 && s[0] != 0xc9))
		return 0;
	n = s[9];
	for (k = 0; k < n && o + 32 <= len; k++) {
		char name[8];
		int i, major = (s[o + 14] & 0x0f) << 6 | s[o + 15] >> 2;
		int minor = (s[o + 15] & 0x03) << 8 | s[o + 16];
		int dlen = (s[o + 30] & 0x03) << 8 | s[o + 31];
		for (i = 0; i < 7; i++)
			name[i] = s[o + 2 * i] ? '?' : (s[o + 2 * i + 1] ? s[o + 2 * i + 1] : ' ');
		name[7] = 0;
		for (i = 6; i >= 0 && name[i] == ' '; i--)
			name[i] = 0;
		printf("%s%d.%d %s", printed++ ? ", " : "  ", major, minor, name);
		o += 32 + dlen;
	}
	return printed;
}

static void scan_names(hdtvmate *d)
{
	static uint8_t ts[188 * 2048], sec[4096];
	int seclen = -1, got = 0;
	time_t end = time(NULL) + 3;

	while (!got && time(NULL) < end) {
		int n = hdtvmate_read_ts(d, ts, sizeof(ts), 300), i;
		for (i = 0; i + 188 <= n && !got; i += 188) {
			const uint8_t *p = ts + i;
			int pid = (p[1] & 0x1f) << 8 | p[2], o = 4;
			if (pid != 0x1ffb || !(p[3] & 0x10))
				continue;
			if (p[3] & 0x20)
				o = 5 + p[4];
			if (o >= 188)
				continue;
			if (p[1] & 0x40) {
				int ptr = p[o];
				seclen = 0;
				o += 1 + ptr;
			} else if (seclen < 0) {
				continue;
			}
			if (o < 188 && seclen + 188 - o < (int)sizeof(sec)) {
				memcpy(sec + seclen, p + o, 188 - o);
				seclen += 188 - o;
			}
			if (seclen >= 3) {
				int need = 3 + ((sec[1] & 0x0f) << 8 | sec[2]);
				if (seclen >= need) {
					got = print_tvct(sec, need);
					seclen = -1;
				}
			}
		}
	}
	printf("\n");
}

int main(int argc, char **argv)
{
	const char *blob = NULL, *outp = "-", *cmdname;
	double secs = 0;
	hdtvmate *d;
	hdtvmate_status st;
	int i, r;
	char *pos[3] = { 0 };
	int npos = 0;

	for (i = 1; i < argc; i++) {
		if (!strcmp(argv[i], "-b") && i + 1 < argc) blob = argv[++i];
		else if (!strcmp(argv[i], "-o") && i + 1 < argc) outp = argv[++i];
		else if (!strcmp(argv[i], "-t") && i + 1 < argc) secs = atof(argv[++i]);
		else if (npos < 3) pos[npos++] = argv[i];
	}
	cmdname = pos[0];
	if (!cmdname) {
		fprintf(stderr, "usage: %s scan | status | tune <rfN|kHz> [-o file|-] [-t secs] [-b hdtvmate.bin]\n", argv[0]);
		return 2;
	}
	r = hdtvmate_open(&d, blob, -1);
	if (r) {
		fprintf(stderr, "open: %s\n", hdtvmate_strerror(r));
		return 1;
	}
	signal(SIGINT, on_sig);
	signal(SIGTERM, on_sig);

	if (!strcmp(cmdname, "scan")) {
		int rf;
		for (rf = 2; rf <= 36 && !stop; rf++) {
			uint32_t khz = hdtvmate_channel_khz(rf);
			if (hdtvmate_tune(d, khz) == 1 && !hdtvmate_get_status(d, &st)) {
				printf("RF %2d %3u MHz signal %3d%%", rf, khz / 1000, st.strength);
				fflush(stdout);
				scan_names(d);
			}
		}
	} else if (!strcmp(cmdname, "status")) {
		if (!hdtvmate_get_status(d, &st))
			printf("locked=%d signal=%d%% errors=%u\n", st.locked, st.strength, st.errors);
	} else if (!strcmp(cmdname, "tune") && pos[1]) {
		static uint8_t buf[188 * 2048];
		uint32_t khz = parse_freq(pos[1]);
		FILE *f;
		time_t end;
		unsigned long long total = 0;

		r = hdtvmate_tune(d, khz);
		if (r < 0) {
			fprintf(stderr, "tune: %s\n", hdtvmate_strerror(r));
			hdtvmate_close(d);
			return 1;
		}
		hdtvmate_get_status(d, &st);
		fprintf(stderr, "%u kHz: %s, signal %d%%\n", khz, r ? "locked" : "NO LOCK", st.strength);
		if (!strcmp(outp, "-")) {
#ifdef _WIN32
			_setmode(_fileno(stdout), _O_BINARY);
#endif
			f = stdout;
		} else {
			f = fopen(outp, "wb");
		}
		if (!f) {
			perror(outp);
			hdtvmate_close(d);
			return 1;
		}
		end = secs > 0 ? time(NULL) + (time_t)secs : 0;
		while (!stop && (!end || time(NULL) < end)) {
			int n = hdtvmate_read_ts(d, buf, sizeof(buf), 1000);
			if (n < 0) {
				fprintf(stderr, "read: %s\n", hdtvmate_strerror(n));
				break;
			}
			if (n && fwrite(buf, 1, n, f) != (size_t)n)
				break;	/* reader went away */
			total += n;
		}
		if (f != stdout)
			fclose(f);
		fprintf(stderr, "%llu bytes\n", total);
	} else {
		fprintf(stderr, "unknown command\n");
	}
	hdtvmate_close(d);
	return 0;
}
