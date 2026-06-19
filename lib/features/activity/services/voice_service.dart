import 'package:flutter_tts/flutter_tts.dart';
import 'package:flutter/foundation.dart';
import 'activity_service.dart';

/// Servicio de síntesis de voz
///
/// DECISIONES TÉCNICAS:
/// - flutter_tts: soporte nativo Android sin dependencias externas,
///   permite configurar idioma, velocidad y tono fácilmente.
/// - Idioma: español como mínimo, detecta el idioma del sistema.
/// - Se evitan anuncios repetitivos guardando el último estado anunciado.
class VoiceService {
  final FlutterTts _tts = FlutterTts();

  ActivityState? _lastAnnouncedState;

  Future<void> initialize() async {
    await _tts.setLanguage('es-ES');
    await _tts.setSpeechRate(0.5); // velocidad natural
    await _tts.setVolume(1.0);
    await _tts.setPitch(1.0);
  }

  /// Anuncia el estado solo si es diferente al último anunciado
  Future<void> announceActivity(ActivityState state) async {
    // Para otros estados, evitar repetición
    if (state == _lastAnnouncedState) return;

    final message = _messageForState(state);
    await _speak(message);
    _lastAnnouncedState = state;
  }

  Future<void> announceComplete() async {
    await _speak('Confirmación: estás bien');
  }

  String _messageForState(ActivityState state) {
    switch (state) {
      case ActivityState.walking:
        return 'Estás caminando';
      case ActivityState.running:
        return 'Estás corriendo';
      case ActivityState.stationary:
        return 'Te has detenido';
      case ActivityState.fall:
        return '¡Caída detectada!';
    }
  }

  Future<void> _speak(String text) async {
    await _tts.stop();
    await _tts.speak(text);
    debugPrint('VoiceService: "$text"');
  }

  Future<void> stop() async {
    await _tts.stop();
  }

  void dispose() {
    _tts.stop();
  }
}
