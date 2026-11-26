# Reguli de produs și de lucru

## Copiii și datele
- Nicio înregistrare sau imagine nu pleacă de pe telefon. Aplicația nu are SDK-uri de analiză. Cere `INTERNET` doar pentru numărul de instalări (un cod făcut la întâmplare și „android”, o singură dată, se poate opri din Setări); build-ul pică dacă apare `ACCESS_NETWORK_STATE`.
- Modelele (fonemele, Whisper, punctele feței) rulează pe telefon.

## Texte
- Copilul nu vede niciodată „greșit”, roșu sau un sunet de eșec. Fără „diagnostic”, „terapie”, „tratează”, „risc”, „severitate”, „tulburare”.
- Când nu iese, aplicația arată ce să faci altfel, în cel mult 8 cuvinte. Când telefonul nu e sigur, cere „Mai spune o dată”.
- Română cu Ș și Ț cu virgulă (U+0218–U+021B), nu cu sedilă.
- Cât mai puțin text pe ecran: poze, iconițe și voce.

## Audio
- Nicio reducere de zgomot înainte de analiză. Nimic nu se redă cât microfonul e deschis.
- Toate vocile sunt clipuri înregistrate sau generate; aplicația nu folosește vocea sintetică a telefonului.

## Cod
- Ce hotărăște (judecătorul de sunet, sesiunea, conținutul) e Kotlin pur, testat pe PC.
- Pragurile, coeficienții și duratele sunt date în `spec/`, iar versiunea lor se salvează pe fiecare încercare.
- Doar biblioteci cu licență permisivă (Apache-2.0, MIT, BSD, ISC; OFL pentru fonturi).
