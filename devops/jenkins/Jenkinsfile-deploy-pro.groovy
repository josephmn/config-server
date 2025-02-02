@Library('utils') _  // Carga la biblioteca 'utils'

pipeline {
    agent any

    tools {
        jdk 'jdk17'
        maven 'maven3'
    }

    environment {
        NAME_APP = 'config-server'
        NAME_IMG_DOCKER = 'config-server-cer'
        CONTAINER_PORT = '8888'
        HOST_PORT = '8888'
        NETWORK = 'azure-net'
        GIT_CREDENTIALS = credentials('PATH_Jenkins')
        GIT_COMMITTER_NAME = 'josephmn'
        GIT_COMMITTER_EMAIL = 'josephcarlos.jcmn@gmail.com'
    }

    stages {
        stage('Docker Build and Run') {
            when {
                expression { params.DOCKER }
            }
            steps {
                script {
                    echo "######################## : ======> EJECUTANDO DOCKER BUILD AND RUN..."
                }
            }
        }
    }

    post {
        success {
            script {
                if (params.NOTIFICATION) {
                    notifyByMail('SUCCESS', params.CORREO)
                }
            }
        }
        failure {
            script {
                if (params.NOTIFICATION) {
                    notifyByMail('FAIL', params.CORREO)
                }
            }
        }
    }
}
